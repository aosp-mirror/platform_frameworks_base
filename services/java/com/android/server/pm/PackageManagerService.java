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

package com.android.server.pm;

import static android.Manifest.permission.GRANT_REVOKE_PERMISSIONS;
import static android.Manifest.permission.READ_EXTERNAL_STORAGE;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
import static android.system.OsConstants.S_IRWXU;
import static android.system.OsConstants.S_IRGRP;
import static android.system.OsConstants.S_IXGRP;
import static android.system.OsConstants.S_IROTH;
import static android.system.OsConstants.S_IXOTH;
import static android.os.Process.PACKAGE_INFO_GID;
import static android.os.Process.SYSTEM_UID;
import static com.android.internal.util.ArrayUtils.appendInt;
import static com.android.internal.util.ArrayUtils.removeInt;

import com.android.internal.R;
import com.android.internal.app.IMediaContainerService;
import com.android.internal.app.ResolverActivity;
import com.android.internal.content.NativeLibraryHelper;
import com.android.internal.content.NativeLibraryHelper.ApkHandle;
import com.android.internal.content.PackageHelper;
import com.android.internal.util.FastPrintWriter;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.XmlUtils;
import com.android.server.DeviceStorageMonitorService;
import com.android.server.EventLogTags;
import com.android.server.IntentResolver;
import com.android.server.Watchdog;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.app.admin.IDevicePolicyManager;
import android.app.backup.IBackupManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.IntentSender.SendIntentException;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ContainerEncryptionParams;
import android.content.pm.FeatureInfo;
import android.content.pm.IPackageDataObserver;
import android.content.pm.IPackageDeleteObserver;
import android.content.pm.IPackageInstallObserver;
import android.content.pm.IPackageManager;
import android.content.pm.IPackageMoveObserver;
import android.content.pm.IPackageStatsObserver;
import android.content.pm.InstrumentationInfo;
import android.content.pm.ManifestDigest;
import android.content.pm.PackageCleanItem;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInfoLite;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser.ActivityIntentInfo;
import android.content.pm.PackageParser;
import android.content.pm.PackageStats;
import android.content.pm.PackageUserState;
import android.content.pm.ParceledListSlice;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.Signature;
import android.content.pm.VerificationParams;
import android.content.pm.VerifierDeviceIdentity;
import android.content.pm.VerifierInfo;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Environment.UserEnvironment;
import android.os.FileObserver;
import android.os.FileUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.SELinux;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.security.KeyStore;
import android.security.SystemKeyStore;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructStat;
import android.text.TextUtils;
import android.util.AtomicFile;
import android.util.DisplayMetrics;
import android.util.EventLog;
import android.util.Log;
import android.util.LogPrinter;
import android.util.PrintStreamPrinter;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Xml;
import android.view.Display;
import android.view.WindowManager;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.cert.CertificateException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import dalvik.system.DexFile;
import dalvik.system.StaleDexCacheError;
import dalvik.system.VMRuntime;
import libcore.io.IoUtils;

/**
 * Keep track of all those .apks everywhere.
 * 
 * This is very central to the platform's security; please run the unit
 * tests whenever making modifications here:
 * 
mmm frameworks/base/tests/AndroidTests
adb install -r -f out/target/product/passion/data/app/AndroidTests.apk
adb shell am instrument -w -e class com.android.unit_tests.PackageManagerTests com.android.unit_tests/android.test.InstrumentationTestRunner
 * 
 * {@hide}
 */
public class PackageManagerService extends IPackageManager.Stub {
    static final String TAG = "PackageManager";
    static final boolean DEBUG_SETTINGS = false;
    static final boolean DEBUG_PREFERRED = false;
    static final boolean DEBUG_UPGRADE = false;
    private static final boolean DEBUG_INSTALL = false;
    private static final boolean DEBUG_REMOVE = false;
    private static final boolean DEBUG_BROADCASTS = false;
    private static final boolean DEBUG_SHOW_INFO = false;
    private static final boolean DEBUG_PACKAGE_INFO = false;
    private static final boolean DEBUG_INTENT_MATCHING = false;
    private static final boolean DEBUG_PACKAGE_SCANNING = false;
    private static final boolean DEBUG_APP_DIR_OBSERVER = false;
    private static final boolean DEBUG_VERIFY = false;
    private static final boolean DEBUG_DEXOPT = false;

    private static final int RADIO_UID = Process.PHONE_UID;
    private static final int LOG_UID = Process.LOG_UID;
    private static final int NFC_UID = Process.NFC_UID;
    private static final int BLUETOOTH_UID = Process.BLUETOOTH_UID;
    private static final int SHELL_UID = Process.SHELL_UID;

    private static final boolean GET_CERTIFICATES = true;

    private static final int REMOVE_EVENTS =
        FileObserver.CLOSE_WRITE | FileObserver.DELETE | FileObserver.MOVED_FROM;
    private static final int ADD_EVENTS =
        FileObserver.CLOSE_WRITE /*| FileObserver.CREATE*/ | FileObserver.MOVED_TO;

    private static final int OBSERVER_EVENTS = REMOVE_EVENTS | ADD_EVENTS;
    // Suffix used during package installation when copying/moving
    // package apks to install directory.
    private static final String INSTALL_PACKAGE_SUFFIX = "-";

    static final int SCAN_MONITOR = 1<<0;
    static final int SCAN_NO_DEX = 1<<1;
    static final int SCAN_FORCE_DEX = 1<<2;
    static final int SCAN_UPDATE_SIGNATURE = 1<<3;
    static final int SCAN_NEW_INSTALL = 1<<4;
    static final int SCAN_NO_PATHS = 1<<5;
    static final int SCAN_UPDATE_TIME = 1<<6;
    static final int SCAN_DEFER_DEX = 1<<7;
    static final int SCAN_BOOTING = 1<<8;
    static final int SCAN_TRUSTED_OVERLAY = 1<<9;
    static final int SCAN_DELETE_DATA_ON_FAILURES = 1<<10;

    static final int REMOVE_CHATTY = 1<<16;

    /**
     * Timeout (in milliseconds) after which the watchdog should declare that
     * our handler thread is wedged.  The usual default for such things is one
     * minute but we sometimes do very lengthy I/O operations on this thread,
     * such as installing multi-gigabyte applications, so ours needs to be longer.
     */
    private static final long WATCHDOG_TIMEOUT = 1000*60*10;     // ten minutes

    /**
     * Whether verification is enabled by default.
     */
    private static final boolean DEFAULT_VERIFY_ENABLE = true;

    /**
     * The default maximum time to wait for the verification agent to return in
     * milliseconds.
     */
    private static final long DEFAULT_VERIFICATION_TIMEOUT = 10 * 1000;

    /**
     * The default response for package verification timeout.
     *
     * This can be either PackageManager.VERIFICATION_ALLOW or
     * PackageManager.VERIFICATION_REJECT.
     */
    private static final int DEFAULT_VERIFICATION_RESPONSE = PackageManager.VERIFICATION_ALLOW;

    static final String DEFAULT_CONTAINER_PACKAGE = "com.android.defcontainer";

    static final ComponentName DEFAULT_CONTAINER_COMPONENT = new ComponentName(
            DEFAULT_CONTAINER_PACKAGE,
            "com.android.defcontainer.DefaultContainerService");

    private static final String PACKAGE_MIME_TYPE = "application/vnd.android.package-archive";

    private static final String LIB_DIR_NAME = "lib";
    private static final String LIB64_DIR_NAME = "lib64";

    private static final String VENDOR_OVERLAY_DIR = "/vendor/overlay";

    static final String mTempContainerPrefix = "smdl2tmp";

    private static String sPreferredInstructionSet;

    private static final String IDMAP_PREFIX = "/data/resource-cache/";
    private static final String IDMAP_SUFFIX = "@idmap";

    final HandlerThread mHandlerThread = new HandlerThread("PackageManager",
            Process.THREAD_PRIORITY_BACKGROUND);
    final PackageHandler mHandler;

    final int mSdkVersion = Build.VERSION.SDK_INT;
    final String mSdkCodename = "REL".equals(Build.VERSION.CODENAME)
            ? null : Build.VERSION.CODENAME;

    final Context mContext;
    final boolean mFactoryTest;
    final boolean mOnlyCore;
    final DisplayMetrics mMetrics;
    final int mDefParseFlags;
    final String[] mSeparateProcesses;

    // This is where all application persistent data goes.
    final File mAppDataDir;

    // This is where all application persistent data goes for secondary users.
    final File mUserAppDataDir;

    /** The location for ASEC container files on internal storage. */
    final String mAsecInternalPath;

    // This is the object monitoring the framework dir.
    final FileObserver mFrameworkInstallObserver;

    // This is the object monitoring the system app dir.
    final FileObserver mSystemInstallObserver;

    // This is the object monitoring the privileged system app dir.
    final FileObserver mPrivilegedInstallObserver;

    // This is the object monitoring the system app dir.
    final FileObserver mVendorInstallObserver;

    // This is the object monitoring the vendor overlay package dir.
    final FileObserver mVendorOverlayInstallObserver;

    // This is the object monitoring mAppInstallDir.
    final FileObserver mAppInstallObserver;

    // This is the object monitoring mDrmAppPrivateInstallDir.
    final FileObserver mDrmAppInstallObserver;

    // Used for privilege escalation. MUST NOT BE CALLED WITH mPackages
    // LOCK HELD.  Can be called with mInstallLock held.
    final Installer mInstaller;

    final File mAppInstallDir;

    /**
     * Directory to which applications installed internally have native
     * libraries copied.
     */
    private File mAppLibInstallDir;

    // Directory containing the private parts (e.g. code and non-resource assets) of forward-locked
    // apps.
    final File mDrmAppPrivateInstallDir;

    // ----------------------------------------------------------------

    // Lock for state used when installing and doing other long running
    // operations.  Methods that must be called with this lock held have
    // the prefix "LI".
    final Object mInstallLock = new Object();

    // These are the directories in the 3rd party applications installed dir
    // that we have currently loaded packages from.  Keys are the application's
    // installed zip file (absolute codePath), and values are Package.
    final HashMap<String, PackageParser.Package> mAppDirs =
            new HashMap<String, PackageParser.Package>();

    // Information for the parser to write more useful error messages.
    int mLastScanError;

    // ----------------------------------------------------------------

    // Keys are String (package name), values are Package.  This also serves
    // as the lock for the global state.  Methods that must be called with
    // this lock held have the prefix "LP".
    final HashMap<String, PackageParser.Package> mPackages =
            new HashMap<String, PackageParser.Package>();

    // Tracks available target package names -> overlay package paths.
    final HashMap<String, HashMap<String, PackageParser.Package>> mOverlays =
        new HashMap<String, HashMap<String, PackageParser.Package>>();

    final Settings mSettings;
    boolean mRestoredSettings;

    // Group-ids that are given to all packages as read from etc/permissions/*.xml.
    int[] mGlobalGids;

    // These are the built-in uid -> permission mappings that were read from the
    // etc/permissions.xml file.
    final SparseArray<HashSet<String>> mSystemPermissions =
            new SparseArray<HashSet<String>>();

    static final class SharedLibraryEntry {
        final String path;
        final String apk;

        SharedLibraryEntry(String _path, String _apk) {
            path = _path;
            apk = _apk;
        }
    }

    // These are the built-in shared libraries that were read from the
    // etc/permissions.xml file.
    final HashMap<String, SharedLibraryEntry> mSharedLibraries
            = new HashMap<String, SharedLibraryEntry>();

    // Temporary for building the final shared libraries for an .apk.
    String[] mTmpSharedLibraries = null;

    // These are the features this devices supports that were read from the
    // etc/permissions.xml file.
    final HashMap<String, FeatureInfo> mAvailableFeatures =
            new HashMap<String, FeatureInfo>();

    // If mac_permissions.xml was found for seinfo labeling.
    boolean mFoundPolicyFile;

    // If a recursive restorecon of /data/data/<pkg> is needed.
    private boolean mShouldRestoreconData = SELinuxMMAC.shouldRestorecon();

    // All available activities, for your resolving pleasure.
    final ActivityIntentResolver mActivities =
            new ActivityIntentResolver();

    // All available receivers, for your resolving pleasure.
    final ActivityIntentResolver mReceivers =
            new ActivityIntentResolver();

    // All available services, for your resolving pleasure.
    final ServiceIntentResolver mServices = new ServiceIntentResolver();

    // All available providers, for your resolving pleasure.
    final ProviderIntentResolver mProviders = new ProviderIntentResolver();

    // Mapping from provider base names (first directory in content URI codePath)
    // to the provider information.
    final HashMap<String, PackageParser.Provider> mProvidersByAuthority =
            new HashMap<String, PackageParser.Provider>();

    // Mapping from instrumentation class names to info about them.
    final HashMap<ComponentName, PackageParser.Instrumentation> mInstrumentation =
            new HashMap<ComponentName, PackageParser.Instrumentation>();

    // Mapping from permission names to info about them.
    final HashMap<String, PackageParser.PermissionGroup> mPermissionGroups =
            new HashMap<String, PackageParser.PermissionGroup>();

    // Packages whose data we have transfered into another package, thus
    // should no longer exist.
    final HashSet<String> mTransferedPackages = new HashSet<String>();
    
    // Broadcast actions that are only available to the system.
    final HashSet<String> mProtectedBroadcasts = new HashSet<String>();

    /** List of packages waiting for verification. */
    final SparseArray<PackageVerificationState> mPendingVerification
            = new SparseArray<PackageVerificationState>();

    HashSet<PackageParser.Package> mDeferredDexOpt = null;

    /** Token for keys in mPendingVerification. */
    private int mPendingVerificationToken = 0;

    boolean mSystemReady;
    boolean mSafeMode;
    boolean mHasSystemUidErrors;

    ApplicationInfo mAndroidApplication;
    final ActivityInfo mResolveActivity = new ActivityInfo();
    final ResolveInfo mResolveInfo = new ResolveInfo();
    ComponentName mResolveComponentName;
    PackageParser.Package mPlatformPackage;
    ComponentName mCustomResolverComponentName;

    boolean mResolverReplaced = false;

    // Set of pending broadcasts for aggregating enable/disable of components.
    static class PendingPackageBroadcasts {
        // for each user id, a map of <package name -> components within that package>
        final SparseArray<HashMap<String, ArrayList<String>>> mUidMap;

        public PendingPackageBroadcasts() {
            mUidMap = new SparseArray<HashMap<String, ArrayList<String>>>(2);
        }

        public ArrayList<String> get(int userId, String packageName) {
            HashMap<String, ArrayList<String>> packages = getOrAllocate(userId);
            return packages.get(packageName);
        }

        public void put(int userId, String packageName, ArrayList<String> components) {
            HashMap<String, ArrayList<String>> packages = getOrAllocate(userId);
            packages.put(packageName, components);
        }

        public void remove(int userId, String packageName) {
            HashMap<String, ArrayList<String>> packages = mUidMap.get(userId);
            if (packages != null) {
                packages.remove(packageName);
            }
        }

        public void remove(int userId) {
            mUidMap.remove(userId);
        }

        public int userIdCount() {
            return mUidMap.size();
        }

        public int userIdAt(int n) {
            return mUidMap.keyAt(n);
        }

        public HashMap<String, ArrayList<String>> packagesForUserId(int userId) {
            return mUidMap.get(userId);
        }

        public int size() {
            // total number of pending broadcast entries across all userIds
            int num = 0;
            for (int i = 0; i< mUidMap.size(); i++) {
                num += mUidMap.valueAt(i).size();
            }
            return num;
        }

        public void clear() {
            mUidMap.clear();
        }

        private HashMap<String, ArrayList<String>> getOrAllocate(int userId) {
            HashMap<String, ArrayList<String>> map = mUidMap.get(userId);
            if (map == null) {
                map = new HashMap<String, ArrayList<String>>();
                mUidMap.put(userId, map);
            }
            return map;
        }
    }
    final PendingPackageBroadcasts mPendingBroadcasts = new PendingPackageBroadcasts();

    // Service Connection to remote media container service to copy
    // package uri's from external media onto secure containers
    // or internal storage.
    private IMediaContainerService mContainerService = null;

    static final int SEND_PENDING_BROADCAST = 1;
    static final int MCS_BOUND = 3;
    static final int END_COPY = 4;
    static final int INIT_COPY = 5;
    static final int MCS_UNBIND = 6;
    static final int START_CLEANING_PACKAGE = 7;
    static final int FIND_INSTALL_LOC = 8;
    static final int POST_INSTALL = 9;
    static final int MCS_RECONNECT = 10;
    static final int MCS_GIVE_UP = 11;
    static final int UPDATED_MEDIA_STATUS = 12;
    static final int WRITE_SETTINGS = 13;
    static final int WRITE_PACKAGE_RESTRICTIONS = 14;
    static final int PACKAGE_VERIFIED = 15;
    static final int CHECK_PENDING_VERIFICATION = 16;

    static final int WRITE_SETTINGS_DELAY = 10*1000;  // 10 seconds

    // Delay time in millisecs
    static final int BROADCAST_DELAY = 10 * 1000;

    static UserManagerService sUserManager;

    // Stores a list of users whose package restrictions file needs to be updated
    private HashSet<Integer> mDirtyUsers = new HashSet<Integer>();

    final private DefaultContainerConnection mDefContainerConn =
            new DefaultContainerConnection();
    class DefaultContainerConnection implements ServiceConnection {
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (DEBUG_SD_INSTALL) Log.i(TAG, "onServiceConnected");
            IMediaContainerService imcs =
                IMediaContainerService.Stub.asInterface(service);
            mHandler.sendMessage(mHandler.obtainMessage(MCS_BOUND, imcs));
        }

        public void onServiceDisconnected(ComponentName name) {
            if (DEBUG_SD_INSTALL) Log.i(TAG, "onServiceDisconnected");
        }
    };

    // Recordkeeping of restore-after-install operations that are currently in flight
    // between the Package Manager and the Backup Manager
    class PostInstallData {
        public InstallArgs args;
        public PackageInstalledInfo res;

        PostInstallData(InstallArgs _a, PackageInstalledInfo _r) {
            args = _a;
            res = _r;
        }
    };
    final SparseArray<PostInstallData> mRunningInstalls = new SparseArray<PostInstallData>();
    int mNextInstallToken = 1;  // nonzero; will be wrapped back to 1 when ++ overflows

    private final String mRequiredVerifierPackage;

    private final PackageUsage mPackageUsage = new PackageUsage();

    private class PackageUsage {
        private static final int WRITE_INTERVAL
            = (DEBUG_DEXOPT) ? 0 : 30*60*1000; // 30m in ms

        private final Object mFileLock = new Object();
        private final AtomicLong mLastWritten = new AtomicLong(0);
        private final AtomicBoolean mBackgroundWriteRunning = new AtomicBoolean(false);

        private boolean mIsHistoricalPackageUsageAvailable = true;

        boolean isHistoricalPackageUsageAvailable() {
            return mIsHistoricalPackageUsageAvailable;
        }

        void write(boolean force) {
            if (force) {
                writeInternal();
                return;
            }
            if (SystemClock.elapsedRealtime() - mLastWritten.get() < WRITE_INTERVAL
                && !DEBUG_DEXOPT) {
                return;
            }
            if (mBackgroundWriteRunning.compareAndSet(false, true)) {
                new Thread("PackageUsage_DiskWriter") {
                    @Override
                    public void run() {
                        try {
                            writeInternal();
                        } finally {
                            mBackgroundWriteRunning.set(false);
                        }
                    }
                }.start();
            }
        }

        private void writeInternal() {
            synchronized (mPackages) {
                synchronized (mFileLock) {
                    AtomicFile file = getFile();
                    FileOutputStream f = null;
                    try {
                        f = file.startWrite();
                        BufferedOutputStream out = new BufferedOutputStream(f);
                        FileUtils.setPermissions(file.getBaseFile().getPath(), 0660, SYSTEM_UID, PACKAGE_INFO_GID);
                        StringBuilder sb = new StringBuilder();
                        for (PackageParser.Package pkg : mPackages.values()) {
                            if (pkg.mLastPackageUsageTimeInMills == 0) {
                                continue;
                            }
                            sb.setLength(0);
                            sb.append(pkg.packageName);
                            sb.append(' ');
                            sb.append((long)pkg.mLastPackageUsageTimeInMills);
                            sb.append('\n');
                            out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
                        }
                        out.flush();
                        file.finishWrite(f);
                    } catch (IOException e) {
                        if (f != null) {
                            file.failWrite(f);
                        }
                        Log.e(TAG, "Failed to write package usage times", e);
                    }
                }
            }
            mLastWritten.set(SystemClock.elapsedRealtime());
        }

        void readLP() {
            synchronized (mFileLock) {
                AtomicFile file = getFile();
                BufferedInputStream in = null;
                try {
                    in = new BufferedInputStream(file.openRead());
                    StringBuffer sb = new StringBuffer();
                    while (true) {
                        String packageName = readToken(in, sb, ' ');
                        if (packageName == null) {
                            break;
                        }
                        String timeInMillisString = readToken(in, sb, '\n');
                        if (timeInMillisString == null) {
                            throw new IOException("Failed to find last usage time for package "
                                                  + packageName);
                        }
                        PackageParser.Package pkg = mPackages.get(packageName);
                        if (pkg == null) {
                            continue;
                        }
                        long timeInMillis;
                        try {
                            timeInMillis = Long.parseLong(timeInMillisString.toString());
                        } catch (NumberFormatException e) {
                            throw new IOException("Failed to parse " + timeInMillisString
                                                  + " as a long.", e);
                        }
                        pkg.mLastPackageUsageTimeInMills = timeInMillis;
                    }
                } catch (FileNotFoundException expected) {
                    mIsHistoricalPackageUsageAvailable = false;
                } catch (IOException e) {
                    Log.w(TAG, "Failed to read package usage times", e);
                } finally {
                    IoUtils.closeQuietly(in);
                }
            }
            mLastWritten.set(SystemClock.elapsedRealtime());
        }

        private String readToken(InputStream in, StringBuffer sb, char endOfToken)
                throws IOException {
            sb.setLength(0);
            while (true) {
                int ch = in.read();
                if (ch == -1) {
                    if (sb.length() == 0) {
                        return null;
                    }
                    throw new IOException("Unexpected EOF");
                }
                if (ch == endOfToken) {
                    return sb.toString();
                }
                sb.append((char)ch);
            }
        }

        private AtomicFile getFile() {
            File dataDir = Environment.getDataDirectory();
            File systemDir = new File(dataDir, "system");
            File fname = new File(systemDir, "package-usage.list");
            return new AtomicFile(fname);
        }
    }

    class PackageHandler extends Handler {
        private boolean mBound = false;
        final ArrayList<HandlerParams> mPendingInstalls =
            new ArrayList<HandlerParams>();

        private boolean connectToService() {
            if (DEBUG_SD_INSTALL) Log.i(TAG, "Trying to bind to" +
                    " DefaultContainerService");
            Intent service = new Intent().setComponent(DEFAULT_CONTAINER_COMPONENT);
            Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
            if (mContext.bindServiceAsUser(service, mDefContainerConn,
                    Context.BIND_AUTO_CREATE, UserHandle.OWNER)) {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                mBound = true;
                return true;
            }
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            return false;
        }

        private void disconnectService() {
            mContainerService = null;
            mBound = false;
            Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
            mContext.unbindService(mDefContainerConn);
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        }

        PackageHandler(Looper looper) {
            super(looper);
        }

        public void handleMessage(Message msg) {
            try {
                doHandleMessage(msg);
            } finally {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            }
        }
        
        void doHandleMessage(Message msg) {
            switch (msg.what) {
                case INIT_COPY: {
                    HandlerParams params = (HandlerParams) msg.obj;
                    int idx = mPendingInstalls.size();
                    if (DEBUG_INSTALL) Slog.i(TAG, "init_copy idx=" + idx + ": " + params);
                    // If a bind was already initiated we dont really
                    // need to do anything. The pending install
                    // will be processed later on.
                    if (!mBound) {
                        // If this is the only one pending we might
                        // have to bind to the service again.
                        if (!connectToService()) {
                            Slog.e(TAG, "Failed to bind to media container service");
                            params.serviceError();
                            return;
                        } else {
                            // Once we bind to the service, the first
                            // pending request will be processed.
                            mPendingInstalls.add(idx, params);
                        }
                    } else {
                        mPendingInstalls.add(idx, params);
                        // Already bound to the service. Just make
                        // sure we trigger off processing the first request.
                        if (idx == 0) {
                            mHandler.sendEmptyMessage(MCS_BOUND);
                        }
                    }
                    break;
                }
                case MCS_BOUND: {
                    if (DEBUG_INSTALL) Slog.i(TAG, "mcs_bound");
                    if (msg.obj != null) {
                        mContainerService = (IMediaContainerService) msg.obj;
                    }
                    if (mContainerService == null) {
                        // Something seriously wrong. Bail out
                        Slog.e(TAG, "Cannot bind to media container service");
                        for (HandlerParams params : mPendingInstalls) {
                            // Indicate service bind error
                            params.serviceError();
                        }
                        mPendingInstalls.clear();
                    } else if (mPendingInstalls.size() > 0) {
                        HandlerParams params = mPendingInstalls.get(0);
                        if (params != null) {
                            if (params.startCopy()) {
                                // We are done...  look for more work or to
                                // go idle.
                                if (DEBUG_SD_INSTALL) Log.i(TAG,
                                        "Checking for more work or unbind...");
                                // Delete pending install
                                if (mPendingInstalls.size() > 0) {
                                    mPendingInstalls.remove(0);
                                }
                                if (mPendingInstalls.size() == 0) {
                                    if (mBound) {
                                        if (DEBUG_SD_INSTALL) Log.i(TAG,
                                                "Posting delayed MCS_UNBIND");
                                        removeMessages(MCS_UNBIND);
                                        Message ubmsg = obtainMessage(MCS_UNBIND);
                                        // Unbind after a little delay, to avoid
                                        // continual thrashing.
                                        sendMessageDelayed(ubmsg, 10000);
                                    }
                                } else {
                                    // There are more pending requests in queue.
                                    // Just post MCS_BOUND message to trigger processing
                                    // of next pending install.
                                    if (DEBUG_SD_INSTALL) Log.i(TAG,
                                            "Posting MCS_BOUND for next woek");
                                    mHandler.sendEmptyMessage(MCS_BOUND);
                                }
                            }
                        }
                    } else {
                        // Should never happen ideally.
                        Slog.w(TAG, "Empty queue");
                    }
                    break;
                }
                case MCS_RECONNECT: {
                    if (DEBUG_INSTALL) Slog.i(TAG, "mcs_reconnect");
                    if (mPendingInstalls.size() > 0) {
                        if (mBound) {
                            disconnectService();
                        }
                        if (!connectToService()) {
                            Slog.e(TAG, "Failed to bind to media container service");
                            for (HandlerParams params : mPendingInstalls) {
                                // Indicate service bind error
                                params.serviceError();
                            }
                            mPendingInstalls.clear();
                        }
                    }
                    break;
                }
                case MCS_UNBIND: {
                    // If there is no actual work left, then time to unbind.
                    if (DEBUG_INSTALL) Slog.i(TAG, "mcs_unbind");

                    if (mPendingInstalls.size() == 0 && mPendingVerification.size() == 0) {
                        if (mBound) {
                            if (DEBUG_INSTALL) Slog.i(TAG, "calling disconnectService()");

                            disconnectService();
                        }
                    } else if (mPendingInstalls.size() > 0) {
                        // There are more pending requests in queue.
                        // Just post MCS_BOUND message to trigger processing
                        // of next pending install.
                        mHandler.sendEmptyMessage(MCS_BOUND);
                    }

                    break;
                }
                case MCS_GIVE_UP: {
                    if (DEBUG_INSTALL) Slog.i(TAG, "mcs_giveup too many retries");
                    mPendingInstalls.remove(0);
                    break;
                }
                case SEND_PENDING_BROADCAST: {
                    String packages[];
                    ArrayList<String> components[];
                    int size = 0;
                    int uids[];
                    Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
                    synchronized (mPackages) {
                        if (mPendingBroadcasts == null) {
                            return;
                        }
                        size = mPendingBroadcasts.size();
                        if (size <= 0) {
                            // Nothing to be done. Just return
                            return;
                        }
                        packages = new String[size];
                        components = new ArrayList[size];
                        uids = new int[size];
                        int i = 0;  // filling out the above arrays

                        for (int n = 0; n < mPendingBroadcasts.userIdCount(); n++) {
                            int packageUserId = mPendingBroadcasts.userIdAt(n);
                            Iterator<Map.Entry<String, ArrayList<String>>> it
                                    = mPendingBroadcasts.packagesForUserId(packageUserId)
                                            .entrySet().iterator();
                            while (it.hasNext() && i < size) {
                                Map.Entry<String, ArrayList<String>> ent = it.next();
                                packages[i] = ent.getKey();
                                components[i] = ent.getValue();
                                PackageSetting ps = mSettings.mPackages.get(ent.getKey());
                                uids[i] = (ps != null)
                                        ? UserHandle.getUid(packageUserId, ps.appId)
                                        : -1;
                                i++;
                            }
                        }
                        size = i;
                        mPendingBroadcasts.clear();
                    }
                    // Send broadcasts
                    for (int i = 0; i < size; i++) {
                        sendPackageChangedBroadcast(packages[i], true, components[i], uids[i]);
                    }
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                    break;
                }
                case START_CLEANING_PACKAGE: {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
                    final String packageName = (String)msg.obj;
                    final int userId = msg.arg1;
                    final boolean andCode = msg.arg2 != 0;
                    synchronized (mPackages) {
                        if (userId == UserHandle.USER_ALL) {
                            int[] users = sUserManager.getUserIds();
                            for (int user : users) {
                                mSettings.addPackageToCleanLPw(
                                        new PackageCleanItem(user, packageName, andCode));
                            }
                        } else {
                            mSettings.addPackageToCleanLPw(
                                    new PackageCleanItem(userId, packageName, andCode));
                        }
                    }
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                    startCleaningPackages();
                } break;
                case POST_INSTALL: {
                    if (DEBUG_INSTALL) Log.v(TAG, "Handling post-install for " + msg.arg1);
                    PostInstallData data = mRunningInstalls.get(msg.arg1);
                    mRunningInstalls.delete(msg.arg1);
                    boolean deleteOld = false;

                    if (data != null) {
                        InstallArgs args = data.args;
                        PackageInstalledInfo res = data.res;

                        if (res.returnCode == PackageManager.INSTALL_SUCCEEDED) {
                            res.removedInfo.sendBroadcast(false, true, false);
                            Bundle extras = new Bundle(1);
                            extras.putInt(Intent.EXTRA_UID, res.uid);
                            // Determine the set of users who are adding this
                            // package for the first time vs. those who are seeing
                            // an update.
                            int[] firstUsers;
                            int[] updateUsers = new int[0];
                            if (res.origUsers == null || res.origUsers.length == 0) {
                                firstUsers = res.newUsers;
                            } else {
                                firstUsers = new int[0];
                                for (int i=0; i<res.newUsers.length; i++) {
                                    int user = res.newUsers[i];
                                    boolean isNew = true;
                                    for (int j=0; j<res.origUsers.length; j++) {
                                        if (res.origUsers[j] == user) {
                                            isNew = false;
                                            break;
                                        }
                                    }
                                    if (isNew) {
                                        int[] newFirst = new int[firstUsers.length+1];
                                        System.arraycopy(firstUsers, 0, newFirst, 0,
                                                firstUsers.length);
                                        newFirst[firstUsers.length] = user;
                                        firstUsers = newFirst;
                                    } else {
                                        int[] newUpdate = new int[updateUsers.length+1];
                                        System.arraycopy(updateUsers, 0, newUpdate, 0,
                                                updateUsers.length);
                                        newUpdate[updateUsers.length] = user;
                                        updateUsers = newUpdate;
                                    }
                                }
                            }
                            sendPackageBroadcast(Intent.ACTION_PACKAGE_ADDED,
                                    res.pkg.applicationInfo.packageName,
                                    extras, null, null, firstUsers);
                            final boolean update = res.removedInfo.removedPackage != null;
                            if (update) {
                                extras.putBoolean(Intent.EXTRA_REPLACING, true);
                            }
                            sendPackageBroadcast(Intent.ACTION_PACKAGE_ADDED,
                                    res.pkg.applicationInfo.packageName,
                                    extras, null, null, updateUsers);
                            if (update) {
                                sendPackageBroadcast(Intent.ACTION_PACKAGE_REPLACED,
                                        res.pkg.applicationInfo.packageName,
                                        extras, null, null, updateUsers);
                                sendPackageBroadcast(Intent.ACTION_MY_PACKAGE_REPLACED,
                                        null, null,
                                        res.pkg.applicationInfo.packageName, null, updateUsers);

                                // treat asec-hosted packages like removable media on upgrade
                                if (isForwardLocked(res.pkg) || isExternal(res.pkg)) {
                                    if (DEBUG_INSTALL) {
                                        Slog.i(TAG, "upgrading pkg " + res.pkg
                                                + " is ASEC-hosted -> AVAILABLE");
                                    }
                                    int[] uidArray = new int[] { res.pkg.applicationInfo.uid };
                                    ArrayList<String> pkgList = new ArrayList<String>(1);
                                    pkgList.add(res.pkg.applicationInfo.packageName);
                                    sendResourcesChangedBroadcast(true, true,
                                            pkgList,uidArray, null);
                                }
                            }
                            if (res.removedInfo.args != null) {
                                // Remove the replaced package's older resources safely now
                                deleteOld = true;
                            }

                            // Log current value of "unknown sources" setting
                            EventLog.writeEvent(EventLogTags.UNKNOWN_SOURCES_ENABLED,
                                getUnknownSourcesSettings());
                        }
                        // Force a gc to clear up things
                        Runtime.getRuntime().gc();
                        // We delete after a gc for applications  on sdcard.
                        if (deleteOld) {
                            synchronized (mInstallLock) {
                                res.removedInfo.args.doPostDeleteLI(true);
                            }
                        }
                        if (args.observer != null) {
                            try {
                                args.observer.packageInstalled(res.name, res.returnCode);
                            } catch (RemoteException e) {
                                Slog.i(TAG, "Observer no longer exists.");
                            }
                        }
                    } else {
                        Slog.e(TAG, "Bogus post-install token " + msg.arg1);
                    }
                } break;
                case UPDATED_MEDIA_STATUS: {
                    if (DEBUG_SD_INSTALL) Log.i(TAG, "Got message UPDATED_MEDIA_STATUS");
                    boolean reportStatus = msg.arg1 == 1;
                    boolean doGc = msg.arg2 == 1;
                    if (DEBUG_SD_INSTALL) Log.i(TAG, "reportStatus=" + reportStatus + ", doGc = " + doGc);
                    if (doGc) {
                        // Force a gc to clear up stale containers.
                        Runtime.getRuntime().gc();
                    }
                    if (msg.obj != null) {
                        @SuppressWarnings("unchecked")
                        Set<AsecInstallArgs> args = (Set<AsecInstallArgs>) msg.obj;
                        if (DEBUG_SD_INSTALL) Log.i(TAG, "Unloading all containers");
                        // Unload containers
                        unloadAllContainers(args);
                    }
                    if (reportStatus) {
                        try {
                            if (DEBUG_SD_INSTALL) Log.i(TAG, "Invoking MountService call back");
                            PackageHelper.getMountService().finishMediaUpdate();
                        } catch (RemoteException e) {
                            Log.e(TAG, "MountService not running?");
                        }
                    }
                } break;
                case WRITE_SETTINGS: {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
                    synchronized (mPackages) {
                        removeMessages(WRITE_SETTINGS);
                        removeMessages(WRITE_PACKAGE_RESTRICTIONS);
                        mSettings.writeLPr();
                        mDirtyUsers.clear();
                    }
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                } break;
                case WRITE_PACKAGE_RESTRICTIONS: {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
                    synchronized (mPackages) {
                        removeMessages(WRITE_PACKAGE_RESTRICTIONS);
                        for (int userId : mDirtyUsers) {
                            mSettings.writePackageRestrictionsLPr(userId);
                        }
                        mDirtyUsers.clear();
                    }
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                } break;
                case CHECK_PENDING_VERIFICATION: {
                    final int verificationId = msg.arg1;
                    final PackageVerificationState state = mPendingVerification.get(verificationId);

                    if ((state != null) && !state.timeoutExtended()) {
                        final InstallArgs args = state.getInstallArgs();
                        Slog.i(TAG, "Verification timed out for " + args.packageURI.toString());
                        mPendingVerification.remove(verificationId);

                        int ret = PackageManager.INSTALL_FAILED_VERIFICATION_FAILURE;

                        if (getDefaultVerificationResponse() == PackageManager.VERIFICATION_ALLOW) {
                            Slog.i(TAG, "Continuing with installation of "
                                    + args.packageURI.toString());
                            state.setVerifierResponse(Binder.getCallingUid(),
                                    PackageManager.VERIFICATION_ALLOW_WITHOUT_SUFFICIENT);
                            broadcastPackageVerified(verificationId, args.packageURI,
                                    PackageManager.VERIFICATION_ALLOW,
                                    state.getInstallArgs().getUser());
                            try {
                                ret = args.copyApk(mContainerService, true);
                            } catch (RemoteException e) {
                                Slog.e(TAG, "Could not contact the ContainerService");
                            }
                        } else {
                            broadcastPackageVerified(verificationId, args.packageURI,
                                    PackageManager.VERIFICATION_REJECT,
                                    state.getInstallArgs().getUser());
                        }

                        processPendingInstall(args, ret);
                        mHandler.sendEmptyMessage(MCS_UNBIND);
                    }
                    break;
                }
                case PACKAGE_VERIFIED: {
                    final int verificationId = msg.arg1;

                    final PackageVerificationState state = mPendingVerification.get(verificationId);
                    if (state == null) {
                        Slog.w(TAG, "Invalid verification token " + verificationId + " received");
                        break;
                    }

                    final PackageVerificationResponse response = (PackageVerificationResponse) msg.obj;

                    state.setVerifierResponse(response.callerUid, response.code);

                    if (state.isVerificationComplete()) {
                        mPendingVerification.remove(verificationId);

                        final InstallArgs args = state.getInstallArgs();

                        int ret;
                        if (state.isInstallAllowed()) {
                            ret = PackageManager.INSTALL_FAILED_INTERNAL_ERROR;
                            broadcastPackageVerified(verificationId, args.packageURI,
                                    response.code, state.getInstallArgs().getUser());
                            try {
                                ret = args.copyApk(mContainerService, true);
                            } catch (RemoteException e) {
                                Slog.e(TAG, "Could not contact the ContainerService");
                            }
                        } else {
                            ret = PackageManager.INSTALL_FAILED_VERIFICATION_FAILURE;
                        }

                        processPendingInstall(args, ret);

                        mHandler.sendEmptyMessage(MCS_UNBIND);
                    }

                    break;
                }
            }
        }
    }

    void scheduleWriteSettingsLocked() {
        if (!mHandler.hasMessages(WRITE_SETTINGS)) {
            mHandler.sendEmptyMessageDelayed(WRITE_SETTINGS, WRITE_SETTINGS_DELAY);
        }
    }

    void scheduleWritePackageRestrictionsLocked(int userId) {
        if (!sUserManager.exists(userId)) return;
        mDirtyUsers.add(userId);
        if (!mHandler.hasMessages(WRITE_PACKAGE_RESTRICTIONS)) {
            mHandler.sendEmptyMessageDelayed(WRITE_PACKAGE_RESTRICTIONS, WRITE_SETTINGS_DELAY);
        }
    }

    public static final IPackageManager main(Context context, Installer installer,
            boolean factoryTest, boolean onlyCore) {
        PackageManagerService m = new PackageManagerService(context, installer,
                factoryTest, onlyCore);
        ServiceManager.addService("package", m);
        return m;
    }

    static String[] splitString(String str, char sep) {
        int count = 1;
        int i = 0;
        while ((i=str.indexOf(sep, i)) >= 0) {
            count++;
            i++;
        }

        String[] res = new String[count];
        i=0;
        count = 0;
        int lastI=0;
        while ((i=str.indexOf(sep, i)) >= 0) {
            res[count] = str.substring(lastI, i);
            count++;
            i++;
            lastI = i;
        }
        res[count] = str.substring(lastI, str.length());
        return res;
    }

    public PackageManagerService(Context context, Installer installer,
            boolean factoryTest, boolean onlyCore) {
        EventLog.writeEvent(EventLogTags.BOOT_PROGRESS_PMS_START,
                SystemClock.uptimeMillis());

        if (mSdkVersion <= 0) {
            Slog.w(TAG, "**** ro.build.version.sdk not set!");
        }

        mContext = context;
        mFactoryTest = factoryTest;
        mOnlyCore = onlyCore;
        mMetrics = new DisplayMetrics();
        mSettings = new Settings(context);
        mSettings.addSharedUserLPw("android.uid.system", Process.SYSTEM_UID,
                ApplicationInfo.FLAG_SYSTEM|ApplicationInfo.FLAG_PRIVILEGED);
        mSettings.addSharedUserLPw("android.uid.phone", RADIO_UID,
                ApplicationInfo.FLAG_SYSTEM|ApplicationInfo.FLAG_PRIVILEGED);
        mSettings.addSharedUserLPw("android.uid.log", LOG_UID,
                ApplicationInfo.FLAG_SYSTEM|ApplicationInfo.FLAG_PRIVILEGED);
        mSettings.addSharedUserLPw("android.uid.nfc", NFC_UID,
                ApplicationInfo.FLAG_SYSTEM|ApplicationInfo.FLAG_PRIVILEGED);
        mSettings.addSharedUserLPw("android.uid.bluetooth", BLUETOOTH_UID,
                ApplicationInfo.FLAG_SYSTEM|ApplicationInfo.FLAG_PRIVILEGED);
        mSettings.addSharedUserLPw("android.uid.shell", SHELL_UID,
                ApplicationInfo.FLAG_SYSTEM|ApplicationInfo.FLAG_PRIVILEGED);

        String separateProcesses = SystemProperties.get("debug.separate_processes");
        if (separateProcesses != null && separateProcesses.length() > 0) {
            if ("*".equals(separateProcesses)) {
                mDefParseFlags = PackageParser.PARSE_IGNORE_PROCESSES;
                mSeparateProcesses = null;
                Slog.w(TAG, "Running with debug.separate_processes: * (ALL)");
            } else {
                mDefParseFlags = 0;
                mSeparateProcesses = separateProcesses.split(",");
                Slog.w(TAG, "Running with debug.separate_processes: "
                        + separateProcesses);
            }
        } else {
            mDefParseFlags = 0;
            mSeparateProcesses = null;
        }

        mInstaller = installer;

        WindowManager wm = (WindowManager)context.getSystemService(Context.WINDOW_SERVICE);
        Display d = wm.getDefaultDisplay();
        d.getMetrics(mMetrics);

        synchronized (mInstallLock) {
        // writer
        synchronized (mPackages) {
            mHandlerThread.start();
            mHandler = new PackageHandler(mHandlerThread.getLooper());
            Watchdog.getInstance().addThread(mHandler, mHandlerThread.getName(),
                    WATCHDOG_TIMEOUT);

            File dataDir = Environment.getDataDirectory();
            mAppDataDir = new File(dataDir, "data");
            mAppInstallDir = new File(dataDir, "app");
            mAppLibInstallDir = new File(dataDir, "app-lib");
            mAsecInternalPath = new File(dataDir, "app-asec").getPath();
            mUserAppDataDir = new File(dataDir, "user");
            mDrmAppPrivateInstallDir = new File(dataDir, "app-private");

            sUserManager = new UserManagerService(context, this,
                    mInstallLock, mPackages);

            readPermissions();

            mFoundPolicyFile = SELinuxMMAC.readInstallPolicy();

            mRestoredSettings = mSettings.readLPw(this, sUserManager.getUsers(false),
                    mSdkVersion, mOnlyCore);

            String customResolverActivity = Resources.getSystem().getString(
                    R.string.config_customResolverActivity);
            if (TextUtils.isEmpty(customResolverActivity)) {
                customResolverActivity = null;
            } else {
                mCustomResolverComponentName = ComponentName.unflattenFromString(
                        customResolverActivity);
            }

            long startTime = SystemClock.uptimeMillis();

            EventLog.writeEvent(EventLogTags.BOOT_PROGRESS_PMS_SYSTEM_SCAN_START,
                    startTime);

            // Set flag to monitor and not change apk file paths when
            // scanning install directories.
            int scanMode = SCAN_MONITOR | SCAN_NO_PATHS | SCAN_DEFER_DEX | SCAN_BOOTING;

            final HashSet<String> alreadyDexOpted = new HashSet<String>();

            /**
             * Add everything in the in the boot class path to the
             * list of process files because dexopt will have been run
             * if necessary during zygote startup.
             */
            String bootClassPath = System.getProperty("java.boot.class.path");
            if (bootClassPath != null) {
                String[] paths = splitString(bootClassPath, ':');
                for (int i=0; i<paths.length; i++) {
                    alreadyDexOpted.add(paths[i]);
                }
            } else {
                Slog.w(TAG, "No BOOTCLASSPATH found!");
            }

            boolean didDexOptLibraryOrTool = false;

            final List<String> allInstructionSets = getAllInstructionSets();
            final String[] dexCodeInstructionSets =
                getDexCodeInstructionSets(allInstructionSets.toArray(new String[allInstructionSets.size()]));

            /**
             * Ensure all external libraries have had dexopt run on them.
             */
            if (mSharedLibraries.size() > 0) {
                // NOTE: For now, we're compiling these system "shared libraries"
                // (and framework jars) into all available architectures. It's possible
                // to compile them only when we come across an app that uses them (there's
                // already logic for that in scanPackageLI) but that adds some complexity.
                for (String dexCodeInstructionSet : dexCodeInstructionSets) {
                    for (SharedLibraryEntry libEntry : mSharedLibraries.values()) {
                        final String lib = libEntry.path;
                        if (lib == null) {
                            continue;
                        }

                        try {
                            byte dexoptRequired = DexFile.isDexOptNeededInternal(lib, null,
                                                                                 dexCodeInstructionSet,
                                                                                 false);
                            if (dexoptRequired != DexFile.UP_TO_DATE) {
                                alreadyDexOpted.add(lib);

                                // The list of "shared libraries" we have at this point is
                                if (dexoptRequired == DexFile.DEXOPT_NEEDED) {
                                    mInstaller.dexopt(lib, Process.SYSTEM_UID, true, dexCodeInstructionSet);
                                } else {
                                    mInstaller.patchoat(lib, Process.SYSTEM_UID, true, dexCodeInstructionSet);
                                }
                                didDexOptLibraryOrTool = true;
                            }
                        } catch (FileNotFoundException e) {
                            Slog.w(TAG, "Library not found: " + lib);
                        } catch (IOException e) {
                            Slog.w(TAG, "Cannot dexopt " + lib + "; is it an APK or JAR? "
                                    + e.getMessage());
                        }
                    }
                }
            }

            File frameworkDir = new File(Environment.getRootDirectory(), "framework");

            // Gross hack for now: we know this file doesn't contain any
            // code, so don't dexopt it to avoid the resulting log spew.
            alreadyDexOpted.add(frameworkDir.getPath() + "/framework-res.apk");

            // Gross hack for now: we know this file is only part of
            // the boot class path for art, so don't dexopt it to
            // avoid the resulting log spew.
            alreadyDexOpted.add(frameworkDir.getPath() + "/core-libart.jar");

            /**
             * And there are a number of commands implemented in Java, which
             * we currently need to do the dexopt on so that they can be
             * run from a non-root shell.
             */
            String[] frameworkFiles = frameworkDir.list();
            if (frameworkFiles != null) {
                // TODO: We could compile these only for the most preferred ABI. We should
                // first double check that the dex files for these commands are not referenced
                // by other system apps.
                for (String dexCodeInstructionSet : dexCodeInstructionSets) {
                    for (int i=0; i<frameworkFiles.length; i++) {
                        File libPath = new File(frameworkDir, frameworkFiles[i]);
                        String path = libPath.getPath();
                        // Skip the file if we already did it.
                        if (alreadyDexOpted.contains(path)) {
                            continue;
                        }
                        // Skip the file if it is not a type we want to dexopt.
                        if (!path.endsWith(".apk") && !path.endsWith(".jar")) {
                            continue;
                        }
                        try {
                            byte dexoptRequired = DexFile.isDexOptNeededInternal(path, null,
                                                                                 dexCodeInstructionSet,
                                                                                 false);
                            if (dexoptRequired == DexFile.DEXOPT_NEEDED) {
                                mInstaller.dexopt(path, Process.SYSTEM_UID, true, dexCodeInstructionSet);
                                didDexOptLibraryOrTool = true;
                            } else if (dexoptRequired == DexFile.PATCHOAT_NEEDED) {
                                mInstaller.patchoat(path, Process.SYSTEM_UID, true, dexCodeInstructionSet);
                                didDexOptLibraryOrTool = true;
                            }
                        } catch (FileNotFoundException e) {
                            Slog.w(TAG, "Jar not found: " + path);
                        } catch (IOException e) {
                            Slog.w(TAG, "Exception reading jar: " + path, e);
                        }
                    }
                }
            }

            if (didDexOptLibraryOrTool) {
                // If we dexopted a library or tool, then something on the system has
                // changed. Consider this significant, and wipe away all other
                // existing dexopt files to ensure we don't leave any dangling around.
                //
                // TODO: This should be revisited because it isn't as good an indicator
                // as it used to be. It used to include the boot classpath but at some point
                // DexFile.isDexOptNeeded started returning false for the boot
                // class path files in all cases. It is very possible in a
                // small maintenance release update that the library and tool
                // jars may be unchanged but APK could be removed resulting in
                // unused dalvik-cache files.
                for (String dexCodeInstructionSet : dexCodeInstructionSets) {
                    mInstaller.pruneDexCache(dexCodeInstructionSet);
                }

                // Additionally, delete all dex files from the root directory
                // since there shouldn't be any there anyway, unless we're upgrading
                // from an older OS version or a build that contained the "old" style
                // flat scheme.
                mInstaller.pruneDexCache(".");
            }

            // Collect vendor overlay packages.
            // (Do this before scanning any apps.)
            // For security and version matching reason, only consider
            // overlay packages if they reside in VENDOR_OVERLAY_DIR.
            File vendorOverlayDir = new File(VENDOR_OVERLAY_DIR);
            mVendorOverlayInstallObserver = new AppDirObserver(
                vendorOverlayDir.getPath(), OBSERVER_EVENTS, true, false);
            mVendorOverlayInstallObserver.startWatching();
            scanDirLI(vendorOverlayDir, PackageParser.PARSE_IS_SYSTEM
                    | PackageParser.PARSE_IS_SYSTEM_DIR, scanMode | SCAN_TRUSTED_OVERLAY, 0);

            // Find base frameworks (resource packages without code).
            mFrameworkInstallObserver = new AppDirObserver(
                frameworkDir.getPath(), OBSERVER_EVENTS, true, false);
            mFrameworkInstallObserver.startWatching();
            scanDirLI(frameworkDir, PackageParser.PARSE_IS_SYSTEM
                    | PackageParser.PARSE_IS_SYSTEM_DIR
                    | PackageParser.PARSE_IS_PRIVILEGED,
                    scanMode | SCAN_NO_DEX, 0);

            // Collected privileged system packages.
            File privilegedAppDir = new File(Environment.getRootDirectory(), "priv-app");
            mPrivilegedInstallObserver = new AppDirObserver(
                    privilegedAppDir.getPath(), OBSERVER_EVENTS, true, true);
            mPrivilegedInstallObserver.startWatching();
                scanDirLI(privilegedAppDir, PackageParser.PARSE_IS_SYSTEM
                        | PackageParser.PARSE_IS_SYSTEM_DIR
                        | PackageParser.PARSE_IS_PRIVILEGED, scanMode, 0);

            // Collect ordinary system packages.
            File systemAppDir = new File(Environment.getRootDirectory(), "app");
            mSystemInstallObserver = new AppDirObserver(
                systemAppDir.getPath(), OBSERVER_EVENTS, true, false);
            mSystemInstallObserver.startWatching();
            scanDirLI(systemAppDir, PackageParser.PARSE_IS_SYSTEM
                    | PackageParser.PARSE_IS_SYSTEM_DIR, scanMode, 0);

            // Collect all vendor packages.
            File vendorAppDir = new File("/vendor/app");
            try {
                vendorAppDir = vendorAppDir.getCanonicalFile();
            } catch (IOException e) {
                // failed to look up canonical path, continue with original one
            }
            mVendorInstallObserver = new AppDirObserver(
                vendorAppDir.getPath(), OBSERVER_EVENTS, true, false);
            mVendorInstallObserver.startWatching();
            scanDirLI(vendorAppDir, PackageParser.PARSE_IS_SYSTEM
                    | PackageParser.PARSE_IS_SYSTEM_DIR, scanMode, 0);

            if (DEBUG_UPGRADE) Log.v(TAG, "Running installd update commands");
            mInstaller.moveFiles();

            // Prune any system packages that no longer exist.
            final List<String> possiblyDeletedUpdatedSystemApps = new ArrayList<String>();
            if (!mOnlyCore) {
                Iterator<PackageSetting> psit = mSettings.mPackages.values().iterator();
                while (psit.hasNext()) {
                    PackageSetting ps = psit.next();

                    /*
                     * If this is not a system app, it can't be a
                     * disable system app.
                     */
                    if ((ps.pkgFlags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                        continue;
                    }

                    /*
                     * If the package is scanned, it's not erased.
                     */
                    final PackageParser.Package scannedPkg = mPackages.get(ps.name);
                    if (scannedPkg != null) {
                        /*
                         * If the system app is both scanned and in the
                         * disabled packages list, then it must have been
                         * added via OTA. Remove it from the currently
                         * scanned package so the previously user-installed
                         * application can be scanned.
                         */
                        if (mSettings.isDisabledSystemPackageLPr(ps.name)) {
                            Slog.i(TAG, "Expecting better updatd system app for " + ps.name
                                    + "; removing system app");
                            removePackageLI(ps, true);
                        }

                        continue;
                    }

                    if (!mSettings.isDisabledSystemPackageLPr(ps.name)) {
                        psit.remove();
                        String msg = "System package " + ps.name
                                + " no longer exists; wiping its data";
                        reportSettingsProblem(Log.WARN, msg);
                        removeDataDirsLI(ps.name);
                    } else {
                        final PackageSetting disabledPs = mSettings.getDisabledSystemPkgLPr(ps.name);
                        if (disabledPs.codePath == null || !disabledPs.codePath.exists()) {
                            possiblyDeletedUpdatedSystemApps.add(ps.name);
                        }
                    }
                }
            }

            //look for any incomplete package installations
            ArrayList<PackageSetting> deletePkgsList = mSettings.getListOfIncompleteInstallPackagesLPr();
            //clean up list
            for(int i = 0; i < deletePkgsList.size(); i++) {
                //clean up here
                cleanupInstallFailedPackage(deletePkgsList.get(i));
            }
            //delete tmp files
            deleteTempPackageFiles();

            // Remove any shared userIDs that have no associated packages
            mSettings.pruneSharedUsersLPw();

            if (!mOnlyCore) {
                EventLog.writeEvent(EventLogTags.BOOT_PROGRESS_PMS_DATA_SCAN_START,
                        SystemClock.uptimeMillis());
                mAppInstallObserver = new AppDirObserver(
                    mAppInstallDir.getPath(), OBSERVER_EVENTS, false, false);
                mAppInstallObserver.startWatching();
                scanDirLI(mAppInstallDir, 0, scanMode, 0);
    
                mDrmAppInstallObserver = new AppDirObserver(
                    mDrmAppPrivateInstallDir.getPath(), OBSERVER_EVENTS, false, false);
                mDrmAppInstallObserver.startWatching();
                scanDirLI(mDrmAppPrivateInstallDir, PackageParser.PARSE_FORWARD_LOCK,
                        scanMode, 0);

                /**
                 * Remove disable package settings for any updated system
                 * apps that were removed via an OTA. If they're not a
                 * previously-updated app, remove them completely.
                 * Otherwise, just revoke their system-level permissions.
                 */
                for (String deletedAppName : possiblyDeletedUpdatedSystemApps) {
                    PackageParser.Package deletedPkg = mPackages.get(deletedAppName);
                    mSettings.removeDisabledSystemPackageLPw(deletedAppName);

                    String msg;
                    if (deletedPkg == null) {
                        msg = "Updated system package " + deletedAppName
                                + " no longer exists; wiping its data";
                        removeDataDirsLI(deletedAppName);
                    } else {
                        msg = "Updated system app + " + deletedAppName
                                + " no longer present; removing system privileges for "
                                + deletedAppName;

                        deletedPkg.applicationInfo.flags &= ~ApplicationInfo.FLAG_SYSTEM;

                        PackageSetting deletedPs = mSettings.mPackages.get(deletedAppName);
                        deletedPs.pkgFlags &= ~ApplicationInfo.FLAG_SYSTEM;
                    }
                    reportSettingsProblem(Log.WARN, msg);
                }
            } else {
                mAppInstallObserver = null;
                mDrmAppInstallObserver = null;
            }

            // Now that we know all of the shared libraries, update all clients to have
            // the correct library paths.
            updateAllSharedLibrariesLPw();

            for (SharedUserSetting setting : mSettings.getAllSharedUsersLPw()) {
                // NOTE: We ignore potential failures here during a system scan (like
                // the rest of the commands above) because there's precious little we
                // can do about it. A settings error is reported, though.
                adjustCpuAbisForSharedUserLPw(setting.packages, null,
                        false /* force dexopt */, false /* defer dexopt */);
            }

            // Now that we know all the packages we are keeping,
            // read and update their last usage times.
            mPackageUsage.readLP();

            EventLog.writeEvent(EventLogTags.BOOT_PROGRESS_PMS_SCAN_END,
                    SystemClock.uptimeMillis());
            Slog.i(TAG, "Time to scan packages: "
                    + ((SystemClock.uptimeMillis()-startTime)/1000f)
                    + " seconds");

            // If the platform SDK has changed since the last time we booted,
            // we need to re-grant app permission to catch any new ones that
            // appear.  This is really a hack, and means that apps can in some
            // cases get permissions that the user didn't initially explicitly
            // allow...  it would be nice to have some better way to handle
            // this situation.
            final boolean regrantPermissions = mSettings.mInternalSdkPlatform
                    != mSdkVersion;
            if (regrantPermissions) Slog.i(TAG, "Platform changed from "
                    + mSettings.mInternalSdkPlatform + " to " + mSdkVersion
                    + "; regranting permissions for internal storage");
            mSettings.mInternalSdkPlatform = mSdkVersion;
            
            updatePermissionsLPw(null, null, UPDATE_PERMISSIONS_ALL
                    | (regrantPermissions
                            ? (UPDATE_PERMISSIONS_REPLACE_PKG|UPDATE_PERMISSIONS_REPLACE_ALL)
                            : 0));

            // If this is the first boot, and it is a normal boot, then
            // we need to initialize the default preferred apps.
            if (!mRestoredSettings && !onlyCore) {
                mSettings.readDefaultPreferredAppsLPw(this, 0);
            }

            // can downgrade to reader
            mSettings.writeLPr();

            EventLog.writeEvent(EventLogTags.BOOT_PROGRESS_PMS_READY,
                    SystemClock.uptimeMillis());

            // Now after opening every single application zip, make sure they
            // are all flushed.  Not really needed, but keeps things nice and
            // tidy.
            Runtime.getRuntime().gc();

            mRequiredVerifierPackage = getRequiredVerifierLPr();
        } // synchronized (mPackages)
        } // synchronized (mInstallLock)
    }

    @Override
    public boolean isFirstBoot() {
        return !mRestoredSettings;
    }

    @Override
    public boolean isOnlyCoreApps() {
        return mOnlyCore;
    }

    private String getRequiredVerifierLPr() {
        final Intent verification = new Intent(Intent.ACTION_PACKAGE_NEEDS_VERIFICATION);
        final List<ResolveInfo> receivers = queryIntentReceivers(verification, PACKAGE_MIME_TYPE,
                PackageManager.GET_DISABLED_COMPONENTS, 0 /* TODO: Which userId? */);

        String requiredVerifier = null;

        final int N = receivers.size();
        for (int i = 0; i < N; i++) {
            final ResolveInfo info = receivers.get(i);

            if (info.activityInfo == null) {
                continue;
            }

            final String packageName = info.activityInfo.packageName;

            final PackageSetting ps = mSettings.mPackages.get(packageName);
            if (ps == null) {
                continue;
            }

            final GrantedPermissions gp = ps.sharedUser != null ? ps.sharedUser : ps;
            if (!gp.grantedPermissions
                    .contains(android.Manifest.permission.PACKAGE_VERIFICATION_AGENT)) {
                continue;
            }

            if (requiredVerifier != null) {
                throw new RuntimeException("There can be only one required verifier");
            }

            requiredVerifier = packageName;
        }

        return requiredVerifier;
    }

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        try {
            return super.onTransact(code, data, reply, flags);
        } catch (RuntimeException e) {
            if (!(e instanceof SecurityException) && !(e instanceof IllegalArgumentException)) {
                Slog.wtf(TAG, "Package Manager Crash", e);
            }
            throw e;
        }
    }

    void cleanupInstallFailedPackage(PackageSetting ps) {
        Slog.i(TAG, "Cleaning up incompletely installed app: " + ps.name);
        removeDataDirsLI(ps.name);
        if (ps.codePath != null) {
            if (!ps.codePath.delete()) {
                Slog.w(TAG, "Unable to remove old code file: " + ps.codePath);
            }
        }
        if (ps.resourcePath != null) {
            if (!ps.resourcePath.delete() && !ps.resourcePath.equals(ps.codePath)) {
                Slog.w(TAG, "Unable to remove old code file: " + ps.resourcePath);
            }
        }
        mSettings.removePackageLPw(ps.name);
    }

    void readPermissions() {
        // Read permissions from .../etc/permission directory.
        File libraryDir = new File(Environment.getRootDirectory(), "etc/permissions");
        if (!libraryDir.exists() || !libraryDir.isDirectory()) {
            Slog.w(TAG, "No directory " + libraryDir + ", skipping");
            return;
        }
        if (!libraryDir.canRead()) {
            Slog.w(TAG, "Directory " + libraryDir + " cannot be read");
            return;
        }

        // Iterate over the files in the directory and scan .xml files
        for (File f : libraryDir.listFiles()) {
            // We'll read platform.xml last
            if (f.getPath().endsWith("etc/permissions/platform.xml")) {
                continue;
            }

            if (!f.getPath().endsWith(".xml")) {
                Slog.i(TAG, "Non-xml file " + f + " in " + libraryDir + " directory, ignoring");
                continue;
            }
            if (!f.canRead()) {
                Slog.w(TAG, "Permissions library file " + f + " cannot be read");
                continue;
            }

            readPermissionsFromXml(f);
        }

        // Read permissions from .../etc/permissions/platform.xml last so it will take precedence
        final File permFile = new File(Environment.getRootDirectory(),
                "etc/permissions/platform.xml");
        readPermissionsFromXml(permFile);
    }

    private void readPermissionsFromXml(File permFile) {
        FileReader permReader = null;
        try {
            permReader = new FileReader(permFile);
        } catch (FileNotFoundException e) {
            Slog.w(TAG, "Couldn't find or open permissions file " + permFile);
            return;
        }

        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(permReader);

            XmlUtils.beginDocument(parser, "permissions");

            while (true) {
                XmlUtils.nextElement(parser);
                if (parser.getEventType() == XmlPullParser.END_DOCUMENT) {
                    break;
                }

                String name = parser.getName();
                if ("group".equals(name)) {
                    String gidStr = parser.getAttributeValue(null, "gid");
                    if (gidStr != null) {
                        int gid = Process.getGidForName(gidStr);
                        mGlobalGids = appendInt(mGlobalGids, gid);
                    } else {
                        Slog.w(TAG, "<group> without gid at "
                                + parser.getPositionDescription());
                    }

                    XmlUtils.skipCurrentTag(parser);
                    continue;
                } else if ("permission".equals(name)) {
                    String perm = parser.getAttributeValue(null, "name");
                    if (perm == null) {
                        Slog.w(TAG, "<permission> without name at "
                                + parser.getPositionDescription());
                        XmlUtils.skipCurrentTag(parser);
                        continue;
                    }
                    perm = perm.intern();
                    readPermission(parser, perm);

                } else if ("assign-permission".equals(name)) {
                    String perm = parser.getAttributeValue(null, "name");
                    if (perm == null) {
                        Slog.w(TAG, "<assign-permission> without name at "
                                + parser.getPositionDescription());
                        XmlUtils.skipCurrentTag(parser);
                        continue;
                    }
                    String uidStr = parser.getAttributeValue(null, "uid");
                    if (uidStr == null) {
                        Slog.w(TAG, "<assign-permission> without uid at "
                                + parser.getPositionDescription());
                        XmlUtils.skipCurrentTag(parser);
                        continue;
                    }
                    int uid = Process.getUidForName(uidStr);
                    if (uid < 0) {
                        Slog.w(TAG, "<assign-permission> with unknown uid \""
                                + uidStr + "\" at "
                                + parser.getPositionDescription());
                        XmlUtils.skipCurrentTag(parser);
                        continue;
                    }
                    perm = perm.intern();
                    HashSet<String> perms = mSystemPermissions.get(uid);
                    if (perms == null) {
                        perms = new HashSet<String>();
                        mSystemPermissions.put(uid, perms);
                    }
                    perms.add(perm);
                    XmlUtils.skipCurrentTag(parser);

                } else if ("library".equals(name)) {
                    String lname = parser.getAttributeValue(null, "name");
                    String lfile = parser.getAttributeValue(null, "file");
                    if (lname == null) {
                        Slog.w(TAG, "<library> without name at "
                                + parser.getPositionDescription());
                    } else if (lfile == null) {
                        Slog.w(TAG, "<library> without file at "
                                + parser.getPositionDescription());
                    } else {
                        //Log.i(TAG, "Got library " + lname + " in " + lfile);
                        mSharedLibraries.put(lname, new SharedLibraryEntry(lfile, null));
                    }
                    XmlUtils.skipCurrentTag(parser);
                    continue;

                } else if ("feature".equals(name)) {
                    String fname = parser.getAttributeValue(null, "name");
                    if (fname == null) {
                        Slog.w(TAG, "<feature> without name at "
                                + parser.getPositionDescription());
                    } else {
                        //Log.i(TAG, "Got feature " + fname);
                        FeatureInfo fi = new FeatureInfo();
                        fi.name = fname;
                        mAvailableFeatures.put(fname, fi);
                    }
                    XmlUtils.skipCurrentTag(parser);
                    continue;

                } else {
                    XmlUtils.skipCurrentTag(parser);
                    continue;
                }

            }
            permReader.close();
        } catch (XmlPullParserException e) {
            Slog.w(TAG, "Got execption parsing permissions.", e);
        } catch (IOException e) {
            Slog.w(TAG, "Got execption parsing permissions.", e);
        }
    }

    void readPermission(XmlPullParser parser, String name)
            throws IOException, XmlPullParserException {

        name = name.intern();

        BasePermission bp = mSettings.mPermissions.get(name);
        if (bp == null) {
            bp = new BasePermission(name, null, BasePermission.TYPE_BUILTIN);
            mSettings.mPermissions.put(name, bp);
        }
        int outerDepth = parser.getDepth();
        int type;
        while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
               && (type != XmlPullParser.END_TAG
                       || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG
                    || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if ("group".equals(tagName)) {
                String gidStr = parser.getAttributeValue(null, "gid");
                if (gidStr != null) {
                    int gid = Process.getGidForName(gidStr);
                    bp.gids = appendInt(bp.gids, gid);
                } else {
                    Slog.w(TAG, "<group> without gid at "
                            + parser.getPositionDescription());
                }
            }
            XmlUtils.skipCurrentTag(parser);
        }
    }

    static int[] appendInts(int[] cur, int[] add) {
        if (add == null) return cur;
        if (cur == null) return add;
        final int N = add.length;
        for (int i=0; i<N; i++) {
            cur = appendInt(cur, add[i]);
        }
        return cur;
    }

    static int[] removeInts(int[] cur, int[] rem) {
        if (rem == null) return cur;
        if (cur == null) return cur;
        final int N = rem.length;
        for (int i=0; i<N; i++) {
            cur = removeInt(cur, rem[i]);
        }
        return cur;
    }

    PackageInfo generatePackageInfo(PackageParser.Package p, int flags, int userId) {
        if (!sUserManager.exists(userId)) return null;
        final PackageSetting ps = (PackageSetting) p.mExtras;
        if (ps == null) {
            return null;
        }
        final GrantedPermissions gp = ps.sharedUser != null ? ps.sharedUser : ps;
        final PackageUserState state = ps.readUserState(userId);
        return PackageParser.generatePackageInfo(p, gp.gids, flags,
                ps.firstInstallTime, ps.lastUpdateTime, gp.grantedPermissions,
                state, userId);
    }

    @Override
    public boolean isPackageAvailable(String packageName, int userId) {
        if (!sUserManager.exists(userId)) return false;
        enforceCrossUserPermission(Binder.getCallingUid(), userId, false, "is package available");
        synchronized (mPackages) {
            PackageParser.Package p = mPackages.get(packageName);
            if (p != null) {
                final PackageSetting ps = (PackageSetting) p.mExtras;
                if (ps != null) {
                    final PackageUserState state = ps.readUserState(userId);
                    if (state != null) {
                        return PackageParser.isAvailable(state);
                    }
                }
            }
        }
        return false;
    }

    @Override
    public PackageInfo getPackageInfo(String packageName, int flags, int userId) {
        if (!sUserManager.exists(userId)) return null;
        enforceCrossUserPermission(Binder.getCallingUid(), userId, false, "get package info");
        // reader
        synchronized (mPackages) {
            PackageParser.Package p = mPackages.get(packageName);
            if (DEBUG_PACKAGE_INFO)
                Log.v(TAG, "getPackageInfo " + packageName + ": " + p);
            if (p != null) {
                return generatePackageInfo(p, flags, userId);
            }
            if((flags & PackageManager.GET_UNINSTALLED_PACKAGES) != 0) {
                return generatePackageInfoFromSettingsLPw(packageName, flags, userId);
            }
        }
        return null;
    }

    @Override
    public String[] currentToCanonicalPackageNames(String[] names) {
        String[] out = new String[names.length];
        // reader
        synchronized (mPackages) {
            for (int i=names.length-1; i>=0; i--) {
                PackageSetting ps = mSettings.mPackages.get(names[i]);
                out[i] = ps != null && ps.realName != null ? ps.realName : names[i];
            }
        }
        return out;
    }
    
    @Override
    public String[] canonicalToCurrentPackageNames(String[] names) {
        String[] out = new String[names.length];
        // reader
        synchronized (mPackages) {
            for (int i=names.length-1; i>=0; i--) {
                String cur = mSettings.mRenamedPackages.get(names[i]);
                out[i] = cur != null ? cur : names[i];
            }
        }
        return out;
    }

    @Override
    public int getPackageUid(String packageName, int userId) {
        if (!sUserManager.exists(userId)) return -1;
        enforceCrossUserPermission(Binder.getCallingUid(), userId, false, "get package uid");
        // reader
        synchronized (mPackages) {
            PackageParser.Package p = mPackages.get(packageName);
            if(p != null) {
                return UserHandle.getUid(userId, p.applicationInfo.uid);
            }
            PackageSetting ps = mSettings.mPackages.get(packageName);
            if((ps == null) || (ps.pkg == null) || (ps.pkg.applicationInfo == null)) {
                return -1;
            }
            p = ps.pkg;
            return p != null ? UserHandle.getUid(userId, p.applicationInfo.uid) : -1;
        }
    }

    @Override
    public int[] getPackageGids(String packageName) {
        // reader
        synchronized (mPackages) {
            PackageParser.Package p = mPackages.get(packageName);
            if (DEBUG_PACKAGE_INFO)
                Log.v(TAG, "getPackageGids" + packageName + ": " + p);
            if (p != null) {
                final PackageSetting ps = (PackageSetting)p.mExtras;
                return ps.getGids();
            }
        }
        // stupid thing to indicate an error.
        return new int[0];
    }

    static final PermissionInfo generatePermissionInfo(
            BasePermission bp, int flags) {
        if (bp.perm != null) {
            return PackageParser.generatePermissionInfo(bp.perm, flags);
        }
        PermissionInfo pi = new PermissionInfo();
        pi.name = bp.name;
        pi.packageName = bp.sourcePackage;
        pi.nonLocalizedLabel = bp.name;
        pi.protectionLevel = bp.protectionLevel;
        return pi;
    }
    
    @Override
    public PermissionInfo getPermissionInfo(String name, int flags) {
        // reader
        synchronized (mPackages) {
            final BasePermission p = mSettings.mPermissions.get(name);
            if (p != null) {
                return generatePermissionInfo(p, flags);
            }
            return null;
        }
    }

    @Override
    public List<PermissionInfo> queryPermissionsByGroup(String group, int flags) {
        // reader
        synchronized (mPackages) {
            ArrayList<PermissionInfo> out = new ArrayList<PermissionInfo>(10);
            for (BasePermission p : mSettings.mPermissions.values()) {
                if (group == null) {
                    if (p.perm == null || p.perm.info.group == null) {
                        out.add(generatePermissionInfo(p, flags));
                    }
                } else {
                    if (p.perm != null && group.equals(p.perm.info.group)) {
                        out.add(PackageParser.generatePermissionInfo(p.perm, flags));
                    }
                }
            }

            if (out.size() > 0) {
                return out;
            }
            return mPermissionGroups.containsKey(group) ? out : null;
        }
    }

    @Override
    public PermissionGroupInfo getPermissionGroupInfo(String name, int flags) {
        // reader
        synchronized (mPackages) {
            return PackageParser.generatePermissionGroupInfo(
                    mPermissionGroups.get(name), flags);
        }
    }

    @Override
    public List<PermissionGroupInfo> getAllPermissionGroups(int flags) {
        // reader
        synchronized (mPackages) {
            final int N = mPermissionGroups.size();
            ArrayList<PermissionGroupInfo> out
                    = new ArrayList<PermissionGroupInfo>(N);
            for (PackageParser.PermissionGroup pg : mPermissionGroups.values()) {
                out.add(PackageParser.generatePermissionGroupInfo(pg, flags));
            }
            return out;
        }
    }

    private ApplicationInfo generateApplicationInfoFromSettingsLPw(String packageName, int flags,
            int userId) {
        if (!sUserManager.exists(userId)) return null;
        PackageSetting ps = mSettings.mPackages.get(packageName);
        if (ps != null) {
            if (ps.pkg == null) {
                PackageInfo pInfo = generatePackageInfoFromSettingsLPw(packageName,
                        flags, userId);
                if (pInfo != null) {
                    return pInfo.applicationInfo;
                }
                return null;
            }
            return PackageParser.generateApplicationInfo(ps.pkg, flags,
                    ps.readUserState(userId), userId);
        }
        return null;
    }

    private PackageInfo generatePackageInfoFromSettingsLPw(String packageName, int flags,
            int userId) {
        if (!sUserManager.exists(userId)) return null;
        PackageSetting ps = mSettings.mPackages.get(packageName);
        if (ps != null) {
            PackageParser.Package pkg = ps.pkg;
            if (pkg == null) {
                if ((flags & PackageManager.GET_UNINSTALLED_PACKAGES) == 0) {
                    return null;
                }
                pkg = new PackageParser.Package(packageName);
                pkg.applicationInfo.packageName = packageName;
                pkg.applicationInfo.flags = ps.pkgFlags | ApplicationInfo.FLAG_IS_DATA_ONLY;
                pkg.applicationInfo.publicSourceDir = ps.resourcePathString;
                pkg.applicationInfo.sourceDir = ps.codePathString;
                pkg.applicationInfo.dataDir =
                        getDataPathForPackage(packageName, 0).getPath();
                pkg.applicationInfo.nativeLibraryDir = ps.nativeLibraryPathString;
                pkg.applicationInfo.cpuAbi = ps.cpuAbiString;
            }
            return generatePackageInfo(pkg, flags, userId);
        }
        return null;
    }

    @Override
    public ApplicationInfo getApplicationInfo(String packageName, int flags, int userId) {
        if (!sUserManager.exists(userId)) return null;
        enforceCrossUserPermission(Binder.getCallingUid(), userId, false, "get application info");
        // writer
        synchronized (mPackages) {
            PackageParser.Package p = mPackages.get(packageName);
            if (DEBUG_PACKAGE_INFO) Log.v(
                    TAG, "getApplicationInfo " + packageName
                    + ": " + p);
            if (p != null) {
                PackageSetting ps = mSettings.mPackages.get(packageName);
                if (ps == null) return null;
                // Note: isEnabledLP() does not apply here - always return info
                return PackageParser.generateApplicationInfo(
                        p, flags, ps.readUserState(userId), userId);
            }
            if ("android".equals(packageName)||"system".equals(packageName)) {
                return mAndroidApplication;
            }
            if ((flags & PackageManager.GET_UNINSTALLED_PACKAGES) != 0) {
                return generateApplicationInfoFromSettingsLPw(packageName, flags, userId);
            }
        }
        return null;
    }


    @Override
    public void freeStorageAndNotify(final long freeStorageSize, final IPackageDataObserver observer) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CLEAR_APP_CACHE, null);
        // Queue up an async operation since clearing cache may take a little while.
        mHandler.post(new Runnable() {
            public void run() {
                mHandler.removeCallbacks(this);
                int retCode = -1;
                synchronized (mInstallLock) {
                    retCode = mInstaller.freeCache(freeStorageSize);
                    if (retCode < 0) {
                        Slog.w(TAG, "Couldn't clear application caches");
                    }
                }
                if (observer != null) {
                    try {
                        observer.onRemoveCompleted(null, (retCode >= 0));
                    } catch (RemoteException e) {
                        Slog.w(TAG, "RemoveException when invoking call back");
                    }
                }
            }
        });
    }

    @Override
    public void freeStorage(final long freeStorageSize, final IntentSender pi) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CLEAR_APP_CACHE, null);
        // Queue up an async operation since clearing cache may take a little while.
        mHandler.post(new Runnable() {
            public void run() {
                mHandler.removeCallbacks(this);
                int retCode = -1;
                synchronized (mInstallLock) {
                    retCode = mInstaller.freeCache(freeStorageSize);
                    if (retCode < 0) {
                        Slog.w(TAG, "Couldn't clear application caches");
                    }
                }
                if(pi != null) {
                    try {
                        // Callback via pending intent
                        int code = (retCode >= 0) ? 1 : 0;
                        pi.sendIntent(null, code, null,
                                null, null);
                    } catch (SendIntentException e1) {
                        Slog.i(TAG, "Failed to send pending intent");
                    }
                }
            }
        });
    }

    @Override
    public ActivityInfo getActivityInfo(ComponentName component, int flags, int userId) {
        if (!sUserManager.exists(userId)) return null;
        enforceCrossUserPermission(Binder.getCallingUid(), userId, false, "get activity info");
        synchronized (mPackages) {
            PackageParser.Activity a = mActivities.mActivities.get(component);

            if (DEBUG_PACKAGE_INFO) Log.v(TAG, "getActivityInfo " + component + ": " + a);
            if (a != null && mSettings.isEnabledLPr(a.info, flags, userId)) {
                PackageSetting ps = mSettings.mPackages.get(component.getPackageName());
                if (ps == null) return null;
                return PackageParser.generateActivityInfo(a, flags, ps.readUserState(userId),
                        userId);
            }
            if (mResolveComponentName.equals(component)) {
                return mResolveActivity;
            }
        }
        return null;
    }

    @Override
    public ActivityInfo getReceiverInfo(ComponentName component, int flags, int userId) {
        if (!sUserManager.exists(userId)) return null;
        enforceCrossUserPermission(Binder.getCallingUid(), userId, false, "get receiver info");
        synchronized (mPackages) {
            PackageParser.Activity a = mReceivers.mActivities.get(component);
            if (DEBUG_PACKAGE_INFO) Log.v(
                TAG, "getReceiverInfo " + component + ": " + a);
            if (a != null && mSettings.isEnabledLPr(a.info, flags, userId)) {
                PackageSetting ps = mSettings.mPackages.get(component.getPackageName());
                if (ps == null) return null;
                return PackageParser.generateActivityInfo(a, flags, ps.readUserState(userId),
                        userId);
            }
        }
        return null;
    }

    @Override
    public ServiceInfo getServiceInfo(ComponentName component, int flags, int userId) {
        if (!sUserManager.exists(userId)) return null;
        enforceCrossUserPermission(Binder.getCallingUid(), userId, false, "get service info");
        synchronized (mPackages) {
            PackageParser.Service s = mServices.mServices.get(component);
            if (DEBUG_PACKAGE_INFO) Log.v(
                TAG, "getServiceInfo " + component + ": " + s);
            if (s != null && mSettings.isEnabledLPr(s.info, flags, userId)) {
                PackageSetting ps = mSettings.mPackages.get(component.getPackageName());
                if (ps == null) return null;
                return PackageParser.generateServiceInfo(s, flags, ps.readUserState(userId),
                        userId);
            }
        }
        return null;
    }

    @Override
    public ProviderInfo getProviderInfo(ComponentName component, int flags, int userId) {
        if (!sUserManager.exists(userId)) return null;
        enforceCrossUserPermission(Binder.getCallingUid(), userId, false, "get provider info");
        synchronized (mPackages) {
            PackageParser.Provider p = mProviders.mProviders.get(component);
            if (DEBUG_PACKAGE_INFO) Log.v(
                TAG, "getProviderInfo " + component + ": " + p);
            if (p != null && mSettings.isEnabledLPr(p.info, flags, userId)) {
                PackageSetting ps = mSettings.mPackages.get(component.getPackageName());
                if (ps == null) return null;
                return PackageParser.generateProviderInfo(p, flags, ps.readUserState(userId),
                        userId);
            }
        }
        return null;
    }

    @Override
    public String[] getSystemSharedLibraryNames() {
        Set<String> libSet;
        synchronized (mPackages) {
            libSet = mSharedLibraries.keySet();
            int size = libSet.size();
            if (size > 0) {
                String[] libs = new String[size];
                libSet.toArray(libs);
                return libs;
            }
        }
        return null;
    }

    @Override
    public FeatureInfo[] getSystemAvailableFeatures() {
        Collection<FeatureInfo> featSet;
        synchronized (mPackages) {
            featSet = mAvailableFeatures.values();
            int size = featSet.size();
            if (size > 0) {
                FeatureInfo[] features = new FeatureInfo[size+1];
                featSet.toArray(features);
                FeatureInfo fi = new FeatureInfo();
                fi.reqGlEsVersion = SystemProperties.getInt("ro.opengles.version",
                        FeatureInfo.GL_ES_VERSION_UNDEFINED);
                features[size] = fi;
                return features;
            }
        }
        return null;
    }

    @Override
    public boolean hasSystemFeature(String name) {
        synchronized (mPackages) {
            return mAvailableFeatures.containsKey(name);
        }
    }

    private void checkValidCaller(int uid, int userId) {
        if (UserHandle.getUserId(uid) == userId || uid == Process.SYSTEM_UID || uid == 0)
            return;

        throw new SecurityException("Caller uid=" + uid
                + " is not privileged to communicate with user=" + userId);
    }

    @Override
    public int checkPermission(String permName, String pkgName) {
        synchronized (mPackages) {
            PackageParser.Package p = mPackages.get(pkgName);
            if (p != null && p.mExtras != null) {
                PackageSetting ps = (PackageSetting)p.mExtras;
                if (ps.sharedUser != null) {
                    if (ps.sharedUser.grantedPermissions.contains(permName)) {
                        return PackageManager.PERMISSION_GRANTED;
                    }
                } else if (ps.grantedPermissions.contains(permName)) {
                    return PackageManager.PERMISSION_GRANTED;
                }
            }
        }
        return PackageManager.PERMISSION_DENIED;
    }

    @Override
    public int checkUidPermission(String permName, int uid) {
        synchronized (mPackages) {
            Object obj = mSettings.getUserIdLPr(UserHandle.getAppId(uid));
            if (obj != null) {
                GrantedPermissions gp = (GrantedPermissions)obj;
                if (gp.grantedPermissions.contains(permName)) {
                    return PackageManager.PERMISSION_GRANTED;
                }
            } else {
                HashSet<String> perms = mSystemPermissions.get(uid);
                if (perms != null && perms.contains(permName)) {
                    return PackageManager.PERMISSION_GRANTED;
                }
            }
        }
        return PackageManager.PERMISSION_DENIED;
    }

    /**
     * Checks if the request is from the system or an app that has INTERACT_ACROSS_USERS
     * or INTERACT_ACROSS_USERS_FULL permissions, if the userid is not for the caller.
     * @param message the message to log on security exception
     * @return
     */
    private void enforceCrossUserPermission(int callingUid, int userId,
            boolean requireFullPermission, String message) {
        if (userId < 0) {
            throw new IllegalArgumentException("Invalid userId " + userId);
        }
        if (userId == UserHandle.getUserId(callingUid)) return;
        if (callingUid != Process.SYSTEM_UID && callingUid != 0) {
            if (requireFullPermission) {
                mContext.enforceCallingOrSelfPermission(
                        android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, message);
            } else {
                try {
                    mContext.enforceCallingOrSelfPermission(
                            android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, message);
                } catch (SecurityException se) {
                    mContext.enforceCallingOrSelfPermission(
                            android.Manifest.permission.INTERACT_ACROSS_USERS, message);
                }
            }
        }
    }

    private BasePermission findPermissionTreeLP(String permName) {
        for(BasePermission bp : mSettings.mPermissionTrees.values()) {
            if (permName.startsWith(bp.name) &&
                    permName.length() > bp.name.length() &&
                    permName.charAt(bp.name.length()) == '.') {
                return bp;
            }
        }
        return null;
    }

    private BasePermission checkPermissionTreeLP(String permName) {
        if (permName != null) {
            BasePermission bp = findPermissionTreeLP(permName);
            if (bp != null) {
                if (bp.uid == UserHandle.getAppId(Binder.getCallingUid())) {
                    return bp;
                }
                throw new SecurityException("Calling uid "
                        + Binder.getCallingUid()
                        + " is not allowed to add to permission tree "
                        + bp.name + " owned by uid " + bp.uid);
            }
        }
        throw new SecurityException("No permission tree found for " + permName);
    }

    static boolean compareStrings(CharSequence s1, CharSequence s2) {
        if (s1 == null) {
            return s2 == null;
        }
        if (s2 == null) {
            return false;
        }
        if (s1.getClass() != s2.getClass()) {
            return false;
        }
        return s1.equals(s2);
    }
    
    static boolean comparePermissionInfos(PermissionInfo pi1, PermissionInfo pi2) {
        if (pi1.icon != pi2.icon) return false;
        if (pi1.logo != pi2.logo) return false;
        if (pi1.protectionLevel != pi2.protectionLevel) return false;
        if (!compareStrings(pi1.name, pi2.name)) return false;
        if (!compareStrings(pi1.nonLocalizedLabel, pi2.nonLocalizedLabel)) return false;
        // We'll take care of setting this one.
        if (!compareStrings(pi1.packageName, pi2.packageName)) return false;
        // These are not currently stored in settings.
        //if (!compareStrings(pi1.group, pi2.group)) return false;
        //if (!compareStrings(pi1.nonLocalizedDescription, pi2.nonLocalizedDescription)) return false;
        //if (pi1.labelRes != pi2.labelRes) return false;
        //if (pi1.descriptionRes != pi2.descriptionRes) return false;
        return true;
    }
    
    boolean addPermissionLocked(PermissionInfo info, boolean async) {
        if (info.labelRes == 0 && info.nonLocalizedLabel == null) {
            throw new SecurityException("Label must be specified in permission");
        }
        BasePermission tree = checkPermissionTreeLP(info.name);
        BasePermission bp = mSettings.mPermissions.get(info.name);
        boolean added = bp == null;
        boolean changed = true;
        int fixedLevel = PermissionInfo.fixProtectionLevel(info.protectionLevel);
        if (added) {
            bp = new BasePermission(info.name, tree.sourcePackage,
                    BasePermission.TYPE_DYNAMIC);
        } else if (bp.type != BasePermission.TYPE_DYNAMIC) {
            throw new SecurityException(
                    "Not allowed to modify non-dynamic permission "
                    + info.name);
        } else {
            if (bp.protectionLevel == fixedLevel
                    && bp.perm.owner.equals(tree.perm.owner)
                    && bp.uid == tree.uid
                    && comparePermissionInfos(bp.perm.info, info)) {
                changed = false;
            }
        }
        bp.protectionLevel = fixedLevel;
        info = new PermissionInfo(info);
        info.protectionLevel = fixedLevel;
        bp.perm = new PackageParser.Permission(tree.perm.owner, info);
        bp.perm.info.packageName = tree.perm.info.packageName;
        bp.uid = tree.uid;
        if (added) {
            mSettings.mPermissions.put(info.name, bp);
        }
        if (changed) {
            if (!async) {
                mSettings.writeLPr();
            } else {
                scheduleWriteSettingsLocked();
            }
        }
        return added;
    }

    @Override
    public boolean addPermission(PermissionInfo info) {
        synchronized (mPackages) {
            return addPermissionLocked(info, false);
        }
    }

    @Override
    public boolean addPermissionAsync(PermissionInfo info) {
        synchronized (mPackages) {
            return addPermissionLocked(info, true);
        }
    }

    @Override
    public void removePermission(String name) {
        synchronized (mPackages) {
            checkPermissionTreeLP(name);
            BasePermission bp = mSettings.mPermissions.get(name);
            if (bp != null) {
                if (bp.type != BasePermission.TYPE_DYNAMIC) {
                    throw new SecurityException(
                            "Not allowed to modify non-dynamic permission "
                            + name);
                }
                mSettings.mPermissions.remove(name);
                mSettings.writeLPr();
            }
        }
    }

    private static void checkGrantRevokePermissions(PackageParser.Package pkg, BasePermission bp) {
        int index = pkg.requestedPermissions.indexOf(bp.name);
        if (index == -1) {
            throw new SecurityException("Package " + pkg.packageName
                    + " has not requested permission " + bp.name);
        }
        boolean isNormal =
                ((bp.protectionLevel&PermissionInfo.PROTECTION_MASK_BASE)
                        == PermissionInfo.PROTECTION_NORMAL);
        boolean isDangerous =
                ((bp.protectionLevel&PermissionInfo.PROTECTION_MASK_BASE)
                        == PermissionInfo.PROTECTION_DANGEROUS);
        boolean isDevelopment =
                ((bp.protectionLevel&PermissionInfo.PROTECTION_FLAG_DEVELOPMENT) != 0);

        if (!isNormal && !isDangerous && !isDevelopment) {
            throw new SecurityException("Permission " + bp.name
                    + " is not a changeable permission type");
        }

        if (isNormal || isDangerous) {
            if (pkg.requestedPermissionsRequired.get(index)) {
                throw new SecurityException("Can't change " + bp.name
                        + ". It is required by the application");
            }
        }
    }

    @Override
    public void grantPermission(String packageName, String permissionName) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.GRANT_REVOKE_PERMISSIONS, null);
        synchronized (mPackages) {
            final PackageParser.Package pkg = mPackages.get(packageName);
            if (pkg == null) {
                throw new IllegalArgumentException("Unknown package: " + packageName);
            }
            final BasePermission bp = mSettings.mPermissions.get(permissionName);
            if (bp == null) {
                throw new IllegalArgumentException("Unknown permission: " + permissionName);
            }

            checkGrantRevokePermissions(pkg, bp);

            final PackageSetting ps = (PackageSetting) pkg.mExtras;
            if (ps == null) {
                return;
            }
            final GrantedPermissions gp = (ps.sharedUser != null) ? ps.sharedUser : ps;
            if (gp.grantedPermissions.add(permissionName)) {
                if (ps.haveGids) {
                    gp.gids = appendInts(gp.gids, bp.gids);
                }
                mSettings.writeLPr();
            }
        }
    }

    @Override
    public void revokePermission(String packageName, String permissionName) {
        int changedAppId = -1;

        synchronized (mPackages) {
            final PackageParser.Package pkg = mPackages.get(packageName);
            if (pkg == null) {
                throw new IllegalArgumentException("Unknown package: " + packageName);
            }
            if (pkg.applicationInfo.uid != Binder.getCallingUid()) {
                mContext.enforceCallingOrSelfPermission(
                        android.Manifest.permission.GRANT_REVOKE_PERMISSIONS, null);
            }
            final BasePermission bp = mSettings.mPermissions.get(permissionName);
            if (bp == null) {
                throw new IllegalArgumentException("Unknown permission: " + permissionName);
            }

            checkGrantRevokePermissions(pkg, bp);

            final PackageSetting ps = (PackageSetting) pkg.mExtras;
            if (ps == null) {
                return;
            }
            final GrantedPermissions gp = (ps.sharedUser != null) ? ps.sharedUser : ps;
            if (gp.grantedPermissions.remove(permissionName)) {
                gp.grantedPermissions.remove(permissionName);
                if (ps.haveGids) {
                    gp.gids = removeInts(gp.gids, bp.gids);
                }
                mSettings.writeLPr();
                changedAppId = ps.appId;
            }
        }

        if (changedAppId >= 0) {
            // We changed the perm on someone, kill its processes.
            IActivityManager am = ActivityManagerNative.getDefault();
            if (am != null) {
                final int callingUserId = UserHandle.getCallingUserId();
                final long ident = Binder.clearCallingIdentity();
                try {
                    //XXX we should only revoke for the calling user's app permissions,
                    // but for now we impact all users.
                    //am.killUid(UserHandle.getUid(callingUserId, changedAppId),
                    //        "revoke " + permissionName);
                    int[] users = sUserManager.getUserIds();
                    for (int user : users) {
                        am.killUid(UserHandle.getUid(user, changedAppId),
                                "revoke " + permissionName);
                    }
                } catch (RemoteException e) {
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        }
    }

    @Override
    public boolean isProtectedBroadcast(String actionName) {
        synchronized (mPackages) {
            return mProtectedBroadcasts.contains(actionName);
        }
    }

    @Override
    public int checkSignatures(String pkg1, String pkg2) {
        synchronized (mPackages) {
            final PackageParser.Package p1 = mPackages.get(pkg1);
            final PackageParser.Package p2 = mPackages.get(pkg2);
            if (p1 == null || p1.mExtras == null
                    || p2 == null || p2.mExtras == null) {
                return PackageManager.SIGNATURE_UNKNOWN_PACKAGE;
            }
            return compareSignatures(p1.mSignatures, p2.mSignatures);
        }
    }

    @Override
    public int checkUidSignatures(int uid1, int uid2) {
        // Map to base uids.
        uid1 = UserHandle.getAppId(uid1);
        uid2 = UserHandle.getAppId(uid2);
        // reader
        synchronized (mPackages) {
            Signature[] s1;
            Signature[] s2;
            Object obj = mSettings.getUserIdLPr(uid1);
            if (obj != null) {
                if (obj instanceof SharedUserSetting) {
                    s1 = ((SharedUserSetting)obj).signatures.mSignatures;
                } else if (obj instanceof PackageSetting) {
                    s1 = ((PackageSetting)obj).signatures.mSignatures;
                } else {
                    return PackageManager.SIGNATURE_UNKNOWN_PACKAGE;
                }
            } else {
                return PackageManager.SIGNATURE_UNKNOWN_PACKAGE;
            }
            obj = mSettings.getUserIdLPr(uid2);
            if (obj != null) {
                if (obj instanceof SharedUserSetting) {
                    s2 = ((SharedUserSetting)obj).signatures.mSignatures;
                } else if (obj instanceof PackageSetting) {
                    s2 = ((PackageSetting)obj).signatures.mSignatures;
                } else {
                    return PackageManager.SIGNATURE_UNKNOWN_PACKAGE;
                }
            } else {
                return PackageManager.SIGNATURE_UNKNOWN_PACKAGE;
            }
            return compareSignatures(s1, s2);
        }
    }

    static int compareSignatures(Signature[] s1, Signature[] s2) {
        if (s1 == null) {
            return s2 == null
                    ? PackageManager.SIGNATURE_NEITHER_SIGNED
                    : PackageManager.SIGNATURE_FIRST_NOT_SIGNED;
        }
        if (s2 == null) {
            return PackageManager.SIGNATURE_SECOND_NOT_SIGNED;
        }
        HashSet<Signature> set1 = new HashSet<Signature>();
        for (Signature sig : s1) {
            set1.add(sig);
        }
        HashSet<Signature> set2 = new HashSet<Signature>();
        for (Signature sig : s2) {
            set2.add(sig);
        }
        // Make sure s2 contains all signatures in s1.
        if (set1.equals(set2)) {
            return PackageManager.SIGNATURE_MATCH;
        }
        return PackageManager.SIGNATURE_NO_MATCH;
    }

    @Override
    public String[] getPackagesForUid(int uid) {
        uid = UserHandle.getAppId(uid);
        // reader
        synchronized (mPackages) {
            Object obj = mSettings.getUserIdLPr(uid);
            if (obj instanceof SharedUserSetting) {
                final SharedUserSetting sus = (SharedUserSetting) obj;
                final int N = sus.packages.size();
                final String[] res = new String[N];
                final Iterator<PackageSetting> it = sus.packages.iterator();
                int i = 0;
                while (it.hasNext()) {
                    res[i++] = it.next().name;
                }
                return res;
            } else if (obj instanceof PackageSetting) {
                final PackageSetting ps = (PackageSetting) obj;
                return new String[] { ps.name };
            }
        }
        return null;
    }

    @Override
    public String getNameForUid(int uid) {
        // reader
        synchronized (mPackages) {
            Object obj = mSettings.getUserIdLPr(UserHandle.getAppId(uid));
            if (obj instanceof SharedUserSetting) {
                final SharedUserSetting sus = (SharedUserSetting) obj;
                return sus.name + ":" + sus.userId;
            } else if (obj instanceof PackageSetting) {
                final PackageSetting ps = (PackageSetting) obj;
                return ps.name;
            }
        }
        return null;
    }

    @Override
    public int getUidForSharedUser(String sharedUserName) {
        if(sharedUserName == null) {
            return -1;
        }
        // reader
        synchronized (mPackages) {
            final SharedUserSetting suid = mSettings.getSharedUserLPw(sharedUserName, 0, false);
            if (suid == null) {
                return -1;
            }
            return suid.userId;
        }
    }

    @Override
    public int getFlagsForUid(int uid) {
        synchronized (mPackages) {
            Object obj = mSettings.getUserIdLPr(UserHandle.getAppId(uid));
            if (obj instanceof SharedUserSetting) {
                final SharedUserSetting sus = (SharedUserSetting) obj;
                return sus.pkgFlags;
            } else if (obj instanceof PackageSetting) {
                final PackageSetting ps = (PackageSetting) obj;
                return ps.pkgFlags;
            }
        }
        return 0;
    }

    @Override
    public ResolveInfo resolveIntent(Intent intent, String resolvedType,
            int flags, int userId) {
        if (!sUserManager.exists(userId)) return null;
        enforceCrossUserPermission(Binder.getCallingUid(), userId, false, "resolve intent");
        List<ResolveInfo> query = queryIntentActivities(intent, resolvedType, flags, userId);
        return chooseBestActivity(intent, resolvedType, flags, query, userId);
    }

    @Override
    public void setLastChosenActivity(Intent intent, String resolvedType, int flags,
            IntentFilter filter, int match, ComponentName activity) {
        final int userId = UserHandle.getCallingUserId();
        if (DEBUG_PREFERRED) {
            Log.v(TAG, "setLastChosenActivity intent=" + intent
                + " resolvedType=" + resolvedType
                + " flags=" + flags
                + " filter=" + filter
                + " match=" + match
                + " activity=" + activity);
            filter.dump(new PrintStreamPrinter(System.out), "    ");
        }
        intent.setComponent(null);
        List<ResolveInfo> query = queryIntentActivities(intent, resolvedType, flags, userId);
        // Find any earlier preferred or last chosen entries and nuke them
        findPreferredActivity(intent, resolvedType,
                flags, query, 0, false, true, false, userId);
        // Add the new activity as the last chosen for this filter
        addPreferredActivityInternal(filter, match, null, activity, false, userId);
    }

    @Override
    public ResolveInfo getLastChosenActivity(Intent intent, String resolvedType, int flags) {
        final int userId = UserHandle.getCallingUserId();
        if (DEBUG_PREFERRED) Log.v(TAG, "Querying last chosen activity for " + intent);
        List<ResolveInfo> query = queryIntentActivities(intent, resolvedType, flags, userId);
        return findPreferredActivity(intent, resolvedType, flags, query, 0,
                false, false, false, userId);
    }

    private ResolveInfo chooseBestActivity(Intent intent, String resolvedType,
            int flags, List<ResolveInfo> query, int userId) {
        if (query != null) {
            final int N = query.size();
            if (N == 1) {
                return query.get(0);
            } else if (N > 1) {
                final boolean debug = ((intent.getFlags() & Intent.FLAG_DEBUG_LOG_RESOLUTION) != 0);
                // If there is more than one activity with the same priority,
                // then let the user decide between them.
                ResolveInfo r0 = query.get(0);
                ResolveInfo r1 = query.get(1);
                if (DEBUG_INTENT_MATCHING || debug) {
                    Slog.v(TAG, r0.activityInfo.name + "=" + r0.priority + " vs "
                            + r1.activityInfo.name + "=" + r1.priority);
                }
                // If the first activity has a higher priority, or a different
                // default, then it is always desireable to pick it.
                if (r0.priority != r1.priority
                        || r0.preferredOrder != r1.preferredOrder
                        || r0.isDefault != r1.isDefault) {
                    return query.get(0);
                }
                // If we have saved a preference for a preferred activity for
                // this Intent, use that.
                ResolveInfo ri = findPreferredActivity(intent, resolvedType,
                        flags, query, r0.priority, true, false, debug, userId);
                if (ri != null) {
                    return ri;
                }
                if (userId != 0) {
                    ri = new ResolveInfo(mResolveInfo);
                    ri.activityInfo = new ActivityInfo(ri.activityInfo);
                    ri.activityInfo.applicationInfo = new ApplicationInfo(
                            ri.activityInfo.applicationInfo);
                    ri.activityInfo.applicationInfo.uid = UserHandle.getUid(userId,
                            UserHandle.getAppId(ri.activityInfo.applicationInfo.uid));
                    return ri;
                }
                return mResolveInfo;
            }
        }
        return null;
    }

    ResolveInfo findPreferredActivity(Intent intent, String resolvedType, int flags,
            List<ResolveInfo> query, int priority, boolean always,
            boolean removeMatches, boolean debug, int userId) {
        if (!sUserManager.exists(userId)) return null;
        // writer
        synchronized (mPackages) {
            if (intent.getSelector() != null) {
                intent = intent.getSelector();
            }
            if (DEBUG_PREFERRED) intent.addFlags(Intent.FLAG_DEBUG_LOG_RESOLUTION);
            PreferredIntentResolver pir = mSettings.mPreferredActivities.get(userId);
            // Get the list of preferred activities that handle the intent
            if (DEBUG_PREFERRED || debug) Slog.v(TAG, "Looking for preferred activities...");
            List<PreferredActivity> prefs = pir != null
                    ? pir.queryIntent(intent, resolvedType,
                            (flags & PackageManager.MATCH_DEFAULT_ONLY) != 0, userId)
                    : null;
            if (prefs != null && prefs.size() > 0) {
                // First figure out how good the original match set is.
                // We will only allow preferred activities that came
                // from the same match quality.
                int match = 0;

                if (DEBUG_PREFERRED || debug) Slog.v(TAG, "Figuring out best match...");

                final int N = query.size();
                for (int j=0; j<N; j++) {
                    final ResolveInfo ri = query.get(j);
                    if (DEBUG_PREFERRED || debug) Slog.v(TAG, "Match for " + ri.activityInfo
                            + ": 0x" + Integer.toHexString(match));
                    if (ri.match > match) {
                        match = ri.match;
                    }
                }

                if (DEBUG_PREFERRED || debug) Slog.v(TAG, "Best match: 0x"
                        + Integer.toHexString(match));

                match &= IntentFilter.MATCH_CATEGORY_MASK;
                final int M = prefs.size();
                for (int i=0; i<M; i++) {
                    final PreferredActivity pa = prefs.get(i);
                    if (DEBUG_PREFERRED || debug) {
                        Slog.v(TAG, "Checking PreferredActivity ds="
                                + (pa.countDataSchemes() > 0 ? pa.getDataScheme(0) : "<none>")
                                + "\n  component=" + pa.mPref.mComponent);
                        pa.dump(new LogPrinter(Log.VERBOSE, TAG, Log.LOG_ID_SYSTEM), "  ");
                    }
                    if (pa.mPref.mMatch != match) {
                        if (DEBUG_PREFERRED || debug) Slog.v(TAG, "Skipping bad match "
                                + Integer.toHexString(pa.mPref.mMatch));
                        continue;
                    }
                    // If it's not an "always" type preferred activity and that's what we're
                    // looking for, skip it.
                    if (always && !pa.mPref.mAlways) {
                        if (DEBUG_PREFERRED || debug) Slog.v(TAG, "Skipping mAlways=false entry");
                        continue;
                    }
                    final ActivityInfo ai = getActivityInfo(pa.mPref.mComponent,
                            flags | PackageManager.GET_DISABLED_COMPONENTS, userId);
                    if (DEBUG_PREFERRED || debug) {
                        Slog.v(TAG, "Found preferred activity:");
                        if (ai != null) {
                            ai.dump(new LogPrinter(Log.VERBOSE, TAG, Log.LOG_ID_SYSTEM), "  ");
                        } else {
                            Slog.v(TAG, "  null");
                        }
                    }
                    if (ai == null) {
                        // This previously registered preferred activity
                        // component is no longer known.  Most likely an update
                        // to the app was installed and in the new version this
                        // component no longer exists.  Clean it up by removing
                        // it from the preferred activities list, and skip it.
                        Slog.w(TAG, "Removing dangling preferred activity: "
                                + pa.mPref.mComponent);
                        pir.removeFilter(pa);
                        continue;
                    }
                    for (int j=0; j<N; j++) {
                        final ResolveInfo ri = query.get(j);
                        if (!ri.activityInfo.applicationInfo.packageName
                                .equals(ai.applicationInfo.packageName)) {
                            continue;
                        }
                        if (!ri.activityInfo.name.equals(ai.name)) {
                            continue;
                        }

                        if (removeMatches) {
                            pir.removeFilter(pa);
                            if (DEBUG_PREFERRED) {
                                Slog.v(TAG, "Removing match " + pa.mPref.mComponent);
                            }
                            break;
                        }

                        // Okay we found a previously set preferred or last chosen app.
                        // If the result set is different from when this
                        // was created, we need to clear it and re-ask the
                        // user their preference, if we're looking for an "always" type entry.
                        if (always && !pa.mPref.sameSet(query, priority)) {
                            Slog.i(TAG, "Result set changed, dropping preferred activity for "
                                    + intent + " type " + resolvedType);
                            if (DEBUG_PREFERRED) {
                                Slog.v(TAG, "Removing preferred activity since set changed "
                                        + pa.mPref.mComponent);
                            }
                            pir.removeFilter(pa);
                            // Re-add the filter as a "last chosen" entry (!always)
                            PreferredActivity lastChosen = new PreferredActivity(
                                    pa, pa.mPref.mMatch, null, pa.mPref.mComponent, false);
                            pir.addFilter(lastChosen);
                            mSettings.writePackageRestrictionsLPr(userId);
                            return null;
                        }

                        // Yay! Either the set matched or we're looking for the last chosen
                        if (DEBUG_PREFERRED || debug) Slog.v(TAG, "Returning preferred activity: "
                                + ri.activityInfo.packageName + "/" + ri.activityInfo.name);
                        mSettings.writePackageRestrictionsLPr(userId);
                        return ri;
                    }
                }
            }
            mSettings.writePackageRestrictionsLPr(userId);
        }
        if (DEBUG_PREFERRED || debug) Slog.v(TAG, "No preferred activity to return");
        return null;
    }

    @Override
    public List<ResolveInfo> queryIntentActivities(Intent intent,
            String resolvedType, int flags, int userId) {
        if (!sUserManager.exists(userId)) return Collections.emptyList();
        enforceCrossUserPermission(Binder.getCallingUid(), userId, false, "query intent activities");
        ComponentName comp = intent.getComponent();
        if (comp == null) {
            if (intent.getSelector() != null) {
                intent = intent.getSelector(); 
                comp = intent.getComponent();
            }
        }

        if (comp != null) {
            final List<ResolveInfo> list = new ArrayList<ResolveInfo>(1);
            final ActivityInfo ai = getActivityInfo(comp, flags, userId);
            if (ai != null) {
                final ResolveInfo ri = new ResolveInfo();
                ri.activityInfo = ai;
                list.add(ri);
            }
            return list;
        }

        // reader
        synchronized (mPackages) {
            final String pkgName = intent.getPackage();
            if (pkgName == null) {
                return mActivities.queryIntent(intent, resolvedType, flags, userId);
            }
            final PackageParser.Package pkg = mPackages.get(pkgName);
            if (pkg != null) {
                return mActivities.queryIntentForPackage(intent, resolvedType, flags,
                        pkg.activities, userId);
            }
            return new ArrayList<ResolveInfo>();
        }
    }

    @Override
    public List<ResolveInfo> queryIntentActivityOptions(ComponentName caller,
            Intent[] specifics, String[] specificTypes, Intent intent,
            String resolvedType, int flags, int userId) {
        if (!sUserManager.exists(userId)) return Collections.emptyList();
        enforceCrossUserPermission(Binder.getCallingUid(), userId, false,
                "query intent activity options");
        final String resultsAction = intent.getAction();

        List<ResolveInfo> results = queryIntentActivities(intent, resolvedType, flags
                | PackageManager.GET_RESOLVED_FILTER, userId);

        if (DEBUG_INTENT_MATCHING) {
            Log.v(TAG, "Query " + intent + ": " + results);
        }

        int specificsPos = 0;
        int N;

        // todo: note that the algorithm used here is O(N^2).  This
        // isn't a problem in our current environment, but if we start running
        // into situations where we have more than 5 or 10 matches then this
        // should probably be changed to something smarter...

        // First we go through and resolve each of the specific items
        // that were supplied, taking care of removing any corresponding
        // duplicate items in the generic resolve list.
        if (specifics != null) {
            for (int i=0; i<specifics.length; i++) {
                final Intent sintent = specifics[i];
                if (sintent == null) {
                    continue;
                }

                if (DEBUG_INTENT_MATCHING) {
                    Log.v(TAG, "Specific #" + i + ": " + sintent);
                }

                String action = sintent.getAction();
                if (resultsAction != null && resultsAction.equals(action)) {
                    // If this action was explicitly requested, then don't
                    // remove things that have it.
                    action = null;
                }

                ResolveInfo ri = null;
                ActivityInfo ai = null;

                ComponentName comp = sintent.getComponent();
                if (comp == null) {
                    ri = resolveIntent(
                        sintent,
                        specificTypes != null ? specificTypes[i] : null,
                            flags, userId);
                    if (ri == null) {
                        continue;
                    }
                    if (ri == mResolveInfo) {
                        // ACK!  Must do something better with this.
                    }
                    ai = ri.activityInfo;
                    comp = new ComponentName(ai.applicationInfo.packageName,
                            ai.name);
                } else {
                    ai = getActivityInfo(comp, flags, userId);
                    if (ai == null) {
                        continue;
                    }
                }

                // Look for any generic query activities that are duplicates
                // of this specific one, and remove them from the results.
                if (DEBUG_INTENT_MATCHING) Log.v(TAG, "Specific #" + i + ": " + ai);
                N = results.size();
                int j;
                for (j=specificsPos; j<N; j++) {
                    ResolveInfo sri = results.get(j);
                    if ((sri.activityInfo.name.equals(comp.getClassName())
                            && sri.activityInfo.applicationInfo.packageName.equals(
                                    comp.getPackageName()))
                        || (action != null && sri.filter.matchAction(action))) {
                        results.remove(j);
                        if (DEBUG_INTENT_MATCHING) Log.v(
                            TAG, "Removing duplicate item from " + j
                            + " due to specific " + specificsPos);
                        if (ri == null) {
                            ri = sri;
                        }
                        j--;
                        N--;
                    }
                }

                // Add this specific item to its proper place.
                if (ri == null) {
                    ri = new ResolveInfo();
                    ri.activityInfo = ai;
                }
                results.add(specificsPos, ri);
                ri.specificIndex = i;
                specificsPos++;
            }
        }

        // Now we go through the remaining generic results and remove any
        // duplicate actions that are found here.
        N = results.size();
        for (int i=specificsPos; i<N-1; i++) {
            final ResolveInfo rii = results.get(i);
            if (rii.filter == null) {
                continue;
            }

            // Iterate over all of the actions of this result's intent
            // filter...  typically this should be just one.
            final Iterator<String> it = rii.filter.actionsIterator();
            if (it == null) {
                continue;
            }
            while (it.hasNext()) {
                final String action = it.next();
                if (resultsAction != null && resultsAction.equals(action)) {
                    // If this action was explicitly requested, then don't
                    // remove things that have it.
                    continue;
                }
                for (int j=i+1; j<N; j++) {
                    final ResolveInfo rij = results.get(j);
                    if (rij.filter != null && rij.filter.hasAction(action)) {
                        results.remove(j);
                        if (DEBUG_INTENT_MATCHING) Log.v(
                            TAG, "Removing duplicate item from " + j
                            + " due to action " + action + " at " + i);
                        j--;
                        N--;
                    }
                }
            }

            // If the caller didn't request filter information, drop it now
            // so we don't have to marshall/unmarshall it.
            if ((flags&PackageManager.GET_RESOLVED_FILTER) == 0) {
                rii.filter = null;
            }
        }

        // Filter out the caller activity if so requested.
        if (caller != null) {
            N = results.size();
            for (int i=0; i<N; i++) {
                ActivityInfo ainfo = results.get(i).activityInfo;
                if (caller.getPackageName().equals(ainfo.applicationInfo.packageName)
                        && caller.getClassName().equals(ainfo.name)) {
                    results.remove(i);
                    break;
                }
            }
        }

        // If the caller didn't request filter information,
        // drop them now so we don't have to
        // marshall/unmarshall it.
        if ((flags&PackageManager.GET_RESOLVED_FILTER) == 0) {
            N = results.size();
            for (int i=0; i<N; i++) {
                results.get(i).filter = null;
            }
        }

        if (DEBUG_INTENT_MATCHING) Log.v(TAG, "Result: " + results);
        return results;
    }

    @Override
    public List<ResolveInfo> queryIntentReceivers(Intent intent, String resolvedType, int flags,
            int userId) {
        if (!sUserManager.exists(userId)) return Collections.emptyList();
        ComponentName comp = intent.getComponent();
        if (comp == null) {
            if (intent.getSelector() != null) {
                intent = intent.getSelector(); 
                comp = intent.getComponent();
            }
        }
        if (comp != null) {
            List<ResolveInfo> list = new ArrayList<ResolveInfo>(1);
            ActivityInfo ai = getReceiverInfo(comp, flags, userId);
            if (ai != null) {
                ResolveInfo ri = new ResolveInfo();
                ri.activityInfo = ai;
                list.add(ri);
            }
            return list;
        }

        // reader
        synchronized (mPackages) {
            String pkgName = intent.getPackage();
            if (pkgName == null) {
                return mReceivers.queryIntent(intent, resolvedType, flags, userId);
            }
            final PackageParser.Package pkg = mPackages.get(pkgName);
            if (pkg != null) {
                return mReceivers.queryIntentForPackage(intent, resolvedType, flags, pkg.receivers,
                        userId);
            }
            return null;
        }
    }

    @Override
    public ResolveInfo resolveService(Intent intent, String resolvedType, int flags, int userId) {
        List<ResolveInfo> query = queryIntentServices(intent, resolvedType, flags, userId);
        if (!sUserManager.exists(userId)) return null;
        if (query != null) {
            if (query.size() >= 1) {
                // If there is more than one service with the same priority,
                // just arbitrarily pick the first one.
                return query.get(0);
            }
        }
        return null;
    }

    @Override
    public List<ResolveInfo> queryIntentServices(Intent intent, String resolvedType, int flags,
            int userId) {
        if (!sUserManager.exists(userId)) return Collections.emptyList();
        ComponentName comp = intent.getComponent();
        if (comp == null) {
            if (intent.getSelector() != null) {
                intent = intent.getSelector(); 
                comp = intent.getComponent();
            }
        }
        if (comp != null) {
            final List<ResolveInfo> list = new ArrayList<ResolveInfo>(1);
            final ServiceInfo si = getServiceInfo(comp, flags, userId);
            if (si != null) {
                final ResolveInfo ri = new ResolveInfo();
                ri.serviceInfo = si;
                list.add(ri);
            }
            return list;
        }

        // reader
        synchronized (mPackages) {
            String pkgName = intent.getPackage();
            if (pkgName == null) {
                return mServices.queryIntent(intent, resolvedType, flags, userId);
            }
            final PackageParser.Package pkg = mPackages.get(pkgName);
            if (pkg != null) {
                return mServices.queryIntentForPackage(intent, resolvedType, flags, pkg.services,
                        userId);
            }
            return null;
        }
    }

    @Override
    public List<ResolveInfo> queryIntentContentProviders(
            Intent intent, String resolvedType, int flags, int userId) {
        if (!sUserManager.exists(userId)) return Collections.emptyList();
        ComponentName comp = intent.getComponent();
        if (comp == null) {
            if (intent.getSelector() != null) {
                intent = intent.getSelector();
                comp = intent.getComponent();
            }
        }
        if (comp != null) {
            final List<ResolveInfo> list = new ArrayList<ResolveInfo>(1);
            final ProviderInfo pi = getProviderInfo(comp, flags, userId);
            if (pi != null) {
                final ResolveInfo ri = new ResolveInfo();
                ri.providerInfo = pi;
                list.add(ri);
            }
            return list;
        }

        // reader
        synchronized (mPackages) {
            String pkgName = intent.getPackage();
            if (pkgName == null) {
                return mProviders.queryIntent(intent, resolvedType, flags, userId);
            }
            final PackageParser.Package pkg = mPackages.get(pkgName);
            if (pkg != null) {
                return mProviders.queryIntentForPackage(
                        intent, resolvedType, flags, pkg.providers, userId);
            }
            return null;
        }
    }

    @Override
    public ParceledListSlice<PackageInfo> getInstalledPackages(int flags, int userId) {
        final boolean listUninstalled = (flags & PackageManager.GET_UNINSTALLED_PACKAGES) != 0;

        enforceCrossUserPermission(Binder.getCallingUid(), userId, true, "get installed packages");

        // writer
        synchronized (mPackages) {
            ArrayList<PackageInfo> list;
            if (listUninstalled) {
                list = new ArrayList<PackageInfo>(mSettings.mPackages.size());
                for (PackageSetting ps : mSettings.mPackages.values()) {
                    PackageInfo pi;
                    if (ps.pkg != null) {
                        pi = generatePackageInfo(ps.pkg, flags, userId);
                    } else {
                        pi = generatePackageInfoFromSettingsLPw(ps.name, flags, userId);
                    }
                    if (pi != null) {
                        list.add(pi);
                    }
                }
            } else {
                list = new ArrayList<PackageInfo>(mPackages.size());
                for (PackageParser.Package p : mPackages.values()) {
                    PackageInfo pi = generatePackageInfo(p, flags, userId);
                    if (pi != null) {
                        list.add(pi);
                    }
                }
            }

            return new ParceledListSlice<PackageInfo>(list);
        }
    }

    private void addPackageHoldingPermissions(ArrayList<PackageInfo> list, PackageSetting ps,
            String[] permissions, boolean[] tmp, int flags, int userId) {
        int numMatch = 0;
        final GrantedPermissions gp = ps.sharedUser != null ? ps.sharedUser : ps;
        for (int i=0; i<permissions.length; i++) {
            if (gp.grantedPermissions.contains(permissions[i])) {
                tmp[i] = true;
                numMatch++;
            } else {
                tmp[i] = false;
            }
        }
        if (numMatch == 0) {
            return;
        }
        PackageInfo pi;
        if (ps.pkg != null) {
            pi = generatePackageInfo(ps.pkg, flags, userId);
        } else {
            pi = generatePackageInfoFromSettingsLPw(ps.name, flags, userId);
        }
        if ((flags&PackageManager.GET_PERMISSIONS) == 0) {
            if (numMatch == permissions.length) {
                pi.requestedPermissions = permissions;
            } else {
                pi.requestedPermissions = new String[numMatch];
                numMatch = 0;
                for (int i=0; i<permissions.length; i++) {
                    if (tmp[i]) {
                        pi.requestedPermissions[numMatch] = permissions[i];
                        numMatch++;
                    }
                }
            }
        }
        list.add(pi);
    }

    @Override
    public ParceledListSlice<PackageInfo> getPackagesHoldingPermissions(
            String[] permissions, int flags, int userId) {
        if (!sUserManager.exists(userId)) return null;
        final boolean listUninstalled = (flags & PackageManager.GET_UNINSTALLED_PACKAGES) != 0;

        // writer
        synchronized (mPackages) {
            ArrayList<PackageInfo> list = new ArrayList<PackageInfo>();
            boolean[] tmpBools = new boolean[permissions.length];
            if (listUninstalled) {
                for (PackageSetting ps : mSettings.mPackages.values()) {
                    addPackageHoldingPermissions(list, ps, permissions, tmpBools, flags, userId);
                }
            } else {
                for (PackageParser.Package pkg : mPackages.values()) {
                    PackageSetting ps = (PackageSetting)pkg.mExtras;
                    if (ps != null) {
                        addPackageHoldingPermissions(list, ps, permissions, tmpBools, flags,
                                userId);
                    }
                }
            }

            return new ParceledListSlice<PackageInfo>(list);
        }
    }

    @Override
    public ParceledListSlice<ApplicationInfo> getInstalledApplications(int flags, int userId) {
        if (!sUserManager.exists(userId)) return null;
        final boolean listUninstalled = (flags & PackageManager.GET_UNINSTALLED_PACKAGES) != 0;

        // writer
        synchronized (mPackages) {
            ArrayList<ApplicationInfo> list;
            if (listUninstalled) {
                list = new ArrayList<ApplicationInfo>(mSettings.mPackages.size());
                for (PackageSetting ps : mSettings.mPackages.values()) {
                    ApplicationInfo ai;
                    if (ps.pkg != null) {
                        ai = PackageParser.generateApplicationInfo(ps.pkg, flags,
                                ps.readUserState(userId), userId);
                    } else {
                        ai = generateApplicationInfoFromSettingsLPw(ps.name, flags, userId);
                    }
                    if (ai != null) {
                        list.add(ai);
                    }
                }
            } else {
                list = new ArrayList<ApplicationInfo>(mPackages.size());
                for (PackageParser.Package p : mPackages.values()) {
                    if (p.mExtras != null) {
                        ApplicationInfo ai = PackageParser.generateApplicationInfo(p, flags,
                                ((PackageSetting)p.mExtras).readUserState(userId), userId);
                        if (ai != null) {
                            list.add(ai);
                        }
                    }
                }
            }

            return new ParceledListSlice<ApplicationInfo>(list);
        }
    }

    public List<ApplicationInfo> getPersistentApplications(int flags) {
        final ArrayList<ApplicationInfo> finalList = new ArrayList<ApplicationInfo>();

        // reader
        synchronized (mPackages) {
            final Iterator<PackageParser.Package> i = mPackages.values().iterator();
            final int userId = UserHandle.getCallingUserId();
            while (i.hasNext()) {
                final PackageParser.Package p = i.next();
                if (p.applicationInfo != null
                        && (p.applicationInfo.flags&ApplicationInfo.FLAG_PERSISTENT) != 0
                        && (!mSafeMode || isSystemApp(p))) {
                    PackageSetting ps = mSettings.mPackages.get(p.packageName);
                    if (ps != null) {
                        ApplicationInfo ai = PackageParser.generateApplicationInfo(p, flags,
                                ps.readUserState(userId), userId);
                        if (ai != null) {
                            finalList.add(ai);
                        }
                    }
                }
            }
        }

        return finalList;
    }

    @Override
    public ProviderInfo resolveContentProvider(String name, int flags, int userId) {
        if (!sUserManager.exists(userId)) return null;
        // reader
        synchronized (mPackages) {
            final PackageParser.Provider provider = mProvidersByAuthority.get(name);
            PackageSetting ps = provider != null
                    ? mSettings.mPackages.get(provider.owner.packageName)
                    : null;
            return ps != null
                    && mSettings.isEnabledLPr(provider.info, flags, userId)
                    && (!mSafeMode || (provider.info.applicationInfo.flags
                            &ApplicationInfo.FLAG_SYSTEM) != 0)
                    ? PackageParser.generateProviderInfo(provider, flags,
                            ps.readUserState(userId), userId)
                    : null;
        }
    }

    /**
     * @deprecated
     */
    @Deprecated
    public void querySyncProviders(List<String> outNames, List<ProviderInfo> outInfo) {
        // reader
        synchronized (mPackages) {
            final Iterator<Map.Entry<String, PackageParser.Provider>> i = mProvidersByAuthority
                    .entrySet().iterator();
            final int userId = UserHandle.getCallingUserId();
            while (i.hasNext()) {
                Map.Entry<String, PackageParser.Provider> entry = i.next();
                PackageParser.Provider p = entry.getValue();
                PackageSetting ps = mSettings.mPackages.get(p.owner.packageName);

                if (ps != null && p.syncable
                        && (!mSafeMode || (p.info.applicationInfo.flags
                                &ApplicationInfo.FLAG_SYSTEM) != 0)) {
                    ProviderInfo info = PackageParser.generateProviderInfo(p, 0,
                            ps.readUserState(userId), userId);
                    if (info != null) {
                        outNames.add(entry.getKey());
                        outInfo.add(info);
                    }
                }
            }
        }
    }

    @Override
    public List<ProviderInfo> queryContentProviders(String processName,
            int uid, int flags) {
        ArrayList<ProviderInfo> finalList = null;
        // reader
        synchronized (mPackages) {
            final Iterator<PackageParser.Provider> i = mProviders.mProviders.values().iterator();
            final int userId = processName != null ?
                    UserHandle.getUserId(uid) : UserHandle.getCallingUserId();
            while (i.hasNext()) {
                final PackageParser.Provider p = i.next();
                PackageSetting ps = mSettings.mPackages.get(p.owner.packageName);
                if (ps != null && p.info.authority != null
                        && (processName == null
                                || (p.info.processName.equals(processName)
                                        && UserHandle.isSameApp(p.info.applicationInfo.uid, uid)))
                        && mSettings.isEnabledLPr(p.info, flags, userId)
                        && (!mSafeMode
                                || (p.info.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0)) {
                    if (finalList == null) {
                        finalList = new ArrayList<ProviderInfo>(3);
                    }
                    ProviderInfo info = PackageParser.generateProviderInfo(p, flags,
                            ps.readUserState(userId), userId);
                    if (info != null) {
                        finalList.add(info);
                    }
                }
            }
        }

        if (finalList != null) {
            Collections.sort(finalList, mProviderInitOrderSorter);
        }

        return finalList;
    }

    @Override
    public InstrumentationInfo getInstrumentationInfo(ComponentName name,
            int flags) {
        // reader
        synchronized (mPackages) {
            final PackageParser.Instrumentation i = mInstrumentation.get(name);
            return PackageParser.generateInstrumentationInfo(i, flags);
        }
    }

    @Override
    public List<InstrumentationInfo> queryInstrumentation(String targetPackage,
            int flags) {
        ArrayList<InstrumentationInfo> finalList =
            new ArrayList<InstrumentationInfo>();

        // reader
        synchronized (mPackages) {
            final Iterator<PackageParser.Instrumentation> i = mInstrumentation.values().iterator();
            while (i.hasNext()) {
                final PackageParser.Instrumentation p = i.next();
                if (targetPackage == null
                        || targetPackage.equals(p.info.targetPackage)) {
                    InstrumentationInfo ii = PackageParser.generateInstrumentationInfo(p,
                            flags);
                    if (ii != null) {
                        finalList.add(ii);
                    }
                }
            }
        }

        return finalList;
    }

    private void createIdmapsForPackageLI(PackageParser.Package pkg) {
        HashMap<String, PackageParser.Package> overlays = mOverlays.get(pkg.packageName);
        if (overlays == null) {
            Slog.w(TAG, "Unable to create idmap for " + pkg.packageName + ": no overlay packages");
            return;
        }
        for (PackageParser.Package opkg : overlays.values()) {
            // Not much to do if idmap fails: we already logged the error
            // and we certainly don't want to abort installation of pkg simply
            // because an overlay didn't fit properly. For these reasons,
            // ignore the return value of createIdmapForPackagePairLI.
            createIdmapForPackagePairLI(pkg, opkg);
        }
    }

    private boolean createIdmapForPackagePairLI(PackageParser.Package pkg,
            PackageParser.Package opkg) {
        if (!opkg.mTrustedOverlay) {
            Slog.w(TAG, "Skipping target and overlay pair " + pkg.mScanPath + " and " +
                    opkg.mScanPath + ": overlay not trusted");
            return false;
        }
        HashMap<String, PackageParser.Package> overlaySet = mOverlays.get(pkg.packageName);
        if (overlaySet == null) {
            Slog.e(TAG, "was about to create idmap for " + pkg.mScanPath + " and " +
                    opkg.mScanPath + " but target package has no known overlays");
            return false;
        }
        final int sharedGid = UserHandle.getSharedAppGid(pkg.applicationInfo.uid);
        if (mInstaller.idmap(pkg.mScanPath, opkg.mScanPath, sharedGid) != 0) {
            Slog.e(TAG, "Failed to generate idmap for " + pkg.mScanPath + " and " + opkg.mScanPath);
            return false;
        }
        PackageParser.Package[] overlayArray =
            overlaySet.values().toArray(new PackageParser.Package[0]);
        Comparator<PackageParser.Package> cmp = new Comparator<PackageParser.Package>() {
            public int compare(PackageParser.Package p1, PackageParser.Package p2) {
                return p1.mOverlayPriority - p2.mOverlayPriority;
            }
        };
        Arrays.sort(overlayArray, cmp);

        pkg.applicationInfo.resourceDirs = new String[overlayArray.length];
        int i = 0;
        for (PackageParser.Package p : overlayArray) {
            pkg.applicationInfo.resourceDirs[i++] = p.applicationInfo.sourceDir;
        }
        return true;
    }

    private void scanDirLI(File dir, int flags, int scanMode, long currentTime) {
        String[] files = dir.list();
        if (files == null) {
            Log.d(TAG, "No files in app dir " + dir);
            return;
        }

        if (DEBUG_PACKAGE_SCANNING) {
            Log.d(TAG, "Scanning app dir " + dir + " scanMode=" + scanMode
                    + " flags=0x" + Integer.toHexString(flags));
        }

        int i;
        for (i=0; i<files.length; i++) {
            File file = new File(dir, files[i]);
            if (!isPackageFilename(files[i])) {
                // Ignore entries which are not apk's
                continue;
            }
            PackageParser.Package pkg = scanPackageLI(file,
                    flags|PackageParser.PARSE_MUST_BE_APK, scanMode, currentTime, null, null);
            // Don't mess around with apps in system partition.
            if (pkg == null && (flags & PackageParser.PARSE_IS_SYSTEM) == 0 &&
                    mLastScanError == PackageManager.INSTALL_FAILED_INVALID_APK) {
                // Delete the apk
                Slog.w(TAG, "Cleaning up failed install of " + file);
                file.delete();
            }
        }
    }

    private static File getSettingsProblemFile() {
        File dataDir = Environment.getDataDirectory();
        File systemDir = new File(dataDir, "system");
        File fname = new File(systemDir, "uiderrors.txt");
        return fname;
    }
    
    static void reportSettingsProblem(int priority, String msg) {
        try {
            File fname = getSettingsProblemFile();
            FileOutputStream out = new FileOutputStream(fname, true);
            PrintWriter pw = new FastPrintWriter(out);
            SimpleDateFormat formatter = new SimpleDateFormat();
            String dateString = formatter.format(new Date(System.currentTimeMillis()));
            pw.println(dateString + ": " + msg);
            pw.close();
            FileUtils.setPermissions(
                    fname.toString(),
                    FileUtils.S_IRWXU|FileUtils.S_IRWXG|FileUtils.S_IROTH,
                    -1, -1);
        } catch (java.io.IOException e) {
        }
        Slog.println(priority, TAG, msg);
    }

    private boolean collectCertificatesLI(PackageParser pp, PackageSetting ps,
            PackageParser.Package pkg, File srcFile, int parseFlags) {
        if (GET_CERTIFICATES) {
            if (ps != null
                    && ps.codePath.equals(srcFile)
                    && ps.timeStamp == srcFile.lastModified()) {
                if (ps.signatures.mSignatures != null
                        && ps.signatures.mSignatures.length != 0) {
                    // Optimization: reuse the existing cached certificates
                    // if the package appears to be unchanged.
                    pkg.mSignatures = ps.signatures.mSignatures;
                    return true;
                }
                
                Slog.w(TAG, "PackageSetting for " + ps.name + " is missing signatures.  Collecting certs again to recover them.");
            } else {
                Log.i(TAG, srcFile.toString() + " changed; collecting certs");
            }
            
            if (!pp.collectCertificates(pkg, parseFlags)) {
                mLastScanError = pp.getParseError();
                return false;
            }
        }
        return true;
    }

    /*
     *  Scan a package and return the newly parsed package.
     *  Returns null in case of errors and the error code is stored in mLastScanError
     */
    private PackageParser.Package scanPackageLI(File scanFile,
            int parseFlags, int scanMode, long currentTime, UserHandle user, String abiOverride) {
        mLastScanError = PackageManager.INSTALL_SUCCEEDED;
        String scanPath = scanFile.getPath();
        if (DEBUG_INSTALL) Slog.d(TAG, "Parsing: " + scanPath);
        parseFlags |= mDefParseFlags;
        PackageParser pp = new PackageParser(scanPath);
        pp.setSeparateProcesses(mSeparateProcesses);
        pp.setOnlyCoreApps(mOnlyCore);
        final PackageParser.Package pkg = pp.parsePackage(scanFile,
                scanPath, mMetrics, parseFlags, (scanMode & SCAN_TRUSTED_OVERLAY) != 0);

        if (pkg == null) {
            mLastScanError = pp.getParseError();
            return null;
        }

        PackageSetting ps = null;
        PackageSetting updatedPkg;
        // reader
        synchronized (mPackages) {
            // Look to see if we already know about this package.
            String oldName = mSettings.mRenamedPackages.get(pkg.packageName);
            if (pkg.mOriginalPackages != null && pkg.mOriginalPackages.contains(oldName)) {
                // This package has been renamed to its original name.  Let's
                // use that.
                ps = mSettings.peekPackageLPr(oldName);
            }
            // If there was no original package, see one for the real package name.
            if (ps == null) {
                ps = mSettings.peekPackageLPr(pkg.packageName);
            }
            // Check to see if this package could be hiding/updating a system
            // package.  Must look for it either under the original or real
            // package name depending on our state.
            updatedPkg = mSettings.getDisabledSystemPkgLPr(ps != null ? ps.name : pkg.packageName);
            if (DEBUG_INSTALL && updatedPkg != null) Slog.d(TAG, "updatedPkg = " + updatedPkg);
        }
        boolean updatedPkgBetter = false;
        // First check if this is a system package that may involve an update
        if (updatedPkg != null && (parseFlags&PackageParser.PARSE_IS_SYSTEM) != 0) {
            if (ps != null && !ps.codePath.equals(scanFile)) {
                // The path has changed from what was last scanned...  check the
                // version of the new path against what we have stored to determine
                // what to do.
                if (DEBUG_INSTALL) Slog.d(TAG, "Path changing from " + ps.codePath);
                if (pkg.mVersionCode < ps.versionCode) {
                    // The system package has been updated and the code path does not match
                    // Ignore entry. Skip it.
                    Log.i(TAG, "Package " + ps.name + " at " + scanFile
                            + " ignored: updated version " + ps.versionCode
                            + " better than this " + pkg.mVersionCode);
                    if (!updatedPkg.codePath.equals(scanFile)) {
                        Slog.w(PackageManagerService.TAG, "Code path for hidden system pkg : "
                                + ps.name + " changing from " + updatedPkg.codePathString
                                + " to " + scanFile);
                        updatedPkg.codePath = scanFile;
                        updatedPkg.codePathString = scanFile.toString();
                        // This is the point at which we know that the system-disk APK
                        // for this package has moved during a reboot (e.g. due to an OTA),
                        // so we need to reevaluate it for privilege policy.
                        if (locationIsPrivileged(scanFile)) {
                            updatedPkg.pkgFlags |= ApplicationInfo.FLAG_PRIVILEGED;
                        }
                    }
                    updatedPkg.pkg = pkg;
                    mLastScanError = PackageManager.INSTALL_FAILED_DUPLICATE_PACKAGE;
                    return null;
                } else {
                    // The current app on the system partition is better than
                    // what we have updated to on the data partition; switch
                    // back to the system partition version.
                    // At this point, its safely assumed that package installation for
                    // apps in system partition will go through. If not there won't be a working
                    // version of the app
                    // writer
                    synchronized (mPackages) {
                        // Just remove the loaded entries from package lists.
                        mPackages.remove(ps.name);
                    }
                    Slog.w(TAG, "Package " + ps.name + " at " + scanFile
                            + "reverting from " + ps.codePathString
                            + ": new version " + pkg.mVersionCode
                            + " better than installed " + ps.versionCode);

                    InstallArgs args = createInstallArgs(packageFlagsToInstallFlags(ps),
                            ps.codePathString, ps.resourcePathString, ps.nativeLibraryPathString,
                            getAppInstructionSetFromSettings(ps));
                    synchronized (mInstallLock) {
                        args.cleanUpResourcesLI();
                    }
                    synchronized (mPackages) {
                        mSettings.enableSystemPackageLPw(ps.name);
                    }
                    updatedPkgBetter = true;
                }
            }
        }

        if (updatedPkg != null) {
            // An updated system app will not have the PARSE_IS_SYSTEM flag set
            // initially
            parseFlags |= PackageParser.PARSE_IS_SYSTEM;

            // An updated privileged app will not have the PARSE_IS_PRIVILEGED
            // flag set initially
            if ((updatedPkg.pkgFlags & ApplicationInfo.FLAG_PRIVILEGED) != 0) {
                parseFlags |= PackageParser.PARSE_IS_PRIVILEGED;
            }
        }
        // Verify certificates against what was last scanned
        if (!collectCertificatesLI(pp, ps, pkg, scanFile, parseFlags)) {
            Slog.w(TAG, "Failed verifying certificates for package:" + pkg.packageName);
            return null;
        }

        /*
         * A new system app appeared, but we already had a non-system one of the
         * same name installed earlier.
         */
        boolean shouldHideSystemApp = false;
        if (updatedPkg == null && ps != null
                && (parseFlags & PackageParser.PARSE_IS_SYSTEM_DIR) != 0 && !isSystemApp(ps)) {
            /*
             * Check to make sure the signatures match first. If they don't,
             * wipe the installed application and its data.
             */
            if (compareSignatures(ps.signatures.mSignatures, pkg.mSignatures)
                    != PackageManager.SIGNATURE_MATCH) {
                if (DEBUG_INSTALL) Slog.d(TAG, "Signature mismatch!");
                deletePackageLI(pkg.packageName, null, true, null, null, 0, null, false);
                ps = null;
            } else {
                /*
                 * If the newly-added system app is an older version than the
                 * already installed version, hide it. It will be scanned later
                 * and re-added like an update.
                 */
                if (pkg.mVersionCode < ps.versionCode) {
                    shouldHideSystemApp = true;
                } else {
                    /*
                     * The newly found system app is a newer version that the
                     * one previously installed. Simply remove the
                     * already-installed application and replace it with our own
                     * while keeping the application data.
                     */
                    Slog.w(TAG, "Package " + ps.name + " at " + scanFile + "reverting from "
                            + ps.codePathString + ": new version " + pkg.mVersionCode
                            + " better than installed " + ps.versionCode);
                    InstallArgs args = createInstallArgs(packageFlagsToInstallFlags(ps),
                            ps.codePathString, ps.resourcePathString, ps.nativeLibraryPathString,
                            getAppInstructionSetFromSettings(ps));
                    synchronized (mInstallLock) {
                        args.cleanUpResourcesLI();
                    }
                }
            }
        }

        // The apk is forward locked (not public) if its code and resources
        // are kept in different files. (except for app in either system or
        // vendor path).
        // TODO grab this value from PackageSettings
        if ((parseFlags & PackageParser.PARSE_IS_SYSTEM_DIR) == 0) {
            if (ps != null && !ps.codePath.equals(ps.resourcePath)) {
                parseFlags |= PackageParser.PARSE_FORWARD_LOCK;
            }
        }

        String codePath = null;
        String resPath = null;
        if ((parseFlags & PackageParser.PARSE_FORWARD_LOCK) != 0 && !updatedPkgBetter) {
            if (ps != null && ps.resourcePathString != null) {
                resPath = ps.resourcePathString;
            } else {
                // Should not happen at all. Just log an error.
                Slog.e(TAG, "Resource path not set for pkg : " + pkg.packageName);
            }
        } else {
            resPath = pkg.mScanPath;
        }

        codePath = pkg.mScanPath;
        // Set application objects path explicitly.
        setApplicationInfoPaths(pkg, codePath, resPath);
        // Note that we invoke the following method only if we are about to unpack an application
        PackageParser.Package scannedPkg = scanPackageLI(pkg, parseFlags, scanMode
                | SCAN_UPDATE_SIGNATURE, currentTime, user, abiOverride);

        /*
         * If the system app should be overridden by a previously installed
         * data, hide the system app now and let the /data/app scan pick it up
         * again.
         */
        if (shouldHideSystemApp) {
            synchronized (mPackages) {
                /*
                 * We have to grant systems permissions before we hide, because
                 * grantPermissions will assume the package update is trying to
                 * expand its permissions.
                 */
                grantPermissionsLPw(pkg, true);
                mSettings.disableSystemPackageLPw(pkg.packageName);
            }
        }

        return scannedPkg;
    }

    private static void setApplicationInfoPaths(PackageParser.Package pkg, String destCodePath,
            String destResPath) {
        pkg.mPath = pkg.mScanPath = destCodePath;
        pkg.applicationInfo.sourceDir = destCodePath;
        pkg.applicationInfo.publicSourceDir = destResPath;
    }

    private static String fixProcessName(String defProcessName,
            String processName, int uid) {
        if (processName == null) {
            return defProcessName;
        }
        return processName;
    }

    private boolean verifySignaturesLP(PackageSetting pkgSetting,
            PackageParser.Package pkg) {
        if (pkgSetting.signatures.mSignatures != null) {
            // Already existing package. Make sure signatures match
            if (compareSignatures(pkgSetting.signatures.mSignatures, pkg.mSignatures) !=
                PackageManager.SIGNATURE_MATCH) {
                    Slog.e(TAG, "Package " + pkg.packageName
                            + " signatures do not match the previously installed version; ignoring!");
                    mLastScanError = PackageManager.INSTALL_FAILED_UPDATE_INCOMPATIBLE;
                    return false;
                }
        }
        // Check for shared user signatures
        if (pkgSetting.sharedUser != null && pkgSetting.sharedUser.signatures.mSignatures != null) {
            if (compareSignatures(pkgSetting.sharedUser.signatures.mSignatures,
                    pkg.mSignatures) != PackageManager.SIGNATURE_MATCH) {
                Slog.e(TAG, "Package " + pkg.packageName
                        + " has no signatures that match those in shared user "
                        + pkgSetting.sharedUser.name + "; ignoring!");
                mLastScanError = PackageManager.INSTALL_FAILED_SHARED_USER_INCOMPATIBLE;
                return false;
            }
        }
        return true;
    }

    /**
     * Enforces that only the system UID or root's UID can call a method exposed
     * via Binder.
     *
     * @param message used as message if SecurityException is thrown
     * @throws SecurityException if the caller is not system or root
     */
    private static final void enforceSystemOrRoot(String message) {
        final int uid = Binder.getCallingUid();
        if (uid != Process.SYSTEM_UID && uid != 0) {
            throw new SecurityException(message);
        }
    }

    @Override
    public void performBootDexOpt() {
        enforceSystemOrRoot("Only the system can request dexopt be performed");

        final HashSet<PackageParser.Package> pkgs;
        synchronized (mPackages) {
            pkgs = mDeferredDexOpt;
            mDeferredDexOpt = null;
        }

        if (pkgs != null) {
            // Filter out packages that aren't recently used.
            //
            // The exception is first boot of a non-eng device, which
            // should do a full dexopt.
            boolean eng = "eng".equals(SystemProperties.get("ro.build.type"));
            if (eng || (!isFirstBoot() && mPackageUsage.isHistoricalPackageUsageAvailable())) {
                // TODO: add a property to control this?
                long dexOptLRUThresholdInMinutes;
                if (eng) {
                    dexOptLRUThresholdInMinutes = 30; // only last 30 minutes of apps for eng builds.
                } else {
                    dexOptLRUThresholdInMinutes = 7 * 24 * 60; // apps used in the 7 days for users.
                }
                long dexOptLRUThresholdInMills = dexOptLRUThresholdInMinutes * 60 * 1000;

                int total = pkgs.size();
                int skipped = 0;
                long now = System.currentTimeMillis();
                for (Iterator<PackageParser.Package> i = pkgs.iterator(); i.hasNext();) {
                    PackageParser.Package pkg = i.next();
                    long then = pkg.mLastPackageUsageTimeInMills;
                    if (then + dexOptLRUThresholdInMills < now) {
                        if (DEBUG_DEXOPT) {
                            Log.i(TAG, "Skipping dexopt of " + pkg.packageName + " last resumed: " +
                                  ((then == 0) ? "never" : new Date(then)));
                        }
                        i.remove();
                        skipped++;
                    }
                }
                if (DEBUG_DEXOPT) {
                    Log.i(TAG, "Skipped optimizing " + skipped + " of " + total);
                }
            }

            int i = 0;
            for (PackageParser.Package pkg : pkgs) {
                i++;
                if (DEBUG_DEXOPT) {
                    Log.i(TAG, "Optimizing app " + i + " of " + pkgs.size()
                          + ": " + pkg.packageName);
                }
                if (!isFirstBoot()) {
                    try {
                        ActivityManagerNative.getDefault().showBootMessage(
                                mContext.getResources().getString(
                                        R.string.android_upgrading_apk,
                                        i, pkgs.size()), true);
                    } catch (RemoteException e) {
                    }
                }
                PackageParser.Package p = pkg;
                synchronized (mInstallLock) {
                    if (p.mDexOptNeeded) {
                        performDexOptLI(p, false /* force dex */, false /* defer */,
                                true /* include dependencies */);
                    }
                }
            }
        }
    }

    @Override
    public boolean performDexOpt(String packageName) {
        enforceSystemOrRoot("Only the system can request dexopt be performed");
        return performDexOpt(packageName, true);
    }

    public boolean performDexOpt(String packageName, boolean updateUsage) {

        PackageParser.Package p;
        synchronized (mPackages) {
            p = mPackages.get(packageName);
            if (p == null) {
                return false;
            }
            if (updateUsage) {
                p.mLastPackageUsageTimeInMills = System.currentTimeMillis();
            }
            mPackageUsage.write(false);
            if (!p.mDexOptNeeded) {
                return false;
            }
        }

        synchronized (mInstallLock) {
            return performDexOptLI(p, false /* force dex */, false /* defer */,
                    true /* include dependencies */) == DEX_OPT_PERFORMED;
        }
    }

    public HashSet<String> getPackagesThatNeedDexOpt() {
        HashSet<String> pkgs = null;
        synchronized (mPackages) {
            for (PackageParser.Package p : mPackages.values()) {
                if (DEBUG_DEXOPT) {
                    Log.i(TAG, p.packageName + " mDexOptNeeded=" + p.mDexOptNeeded);
                }
                if (!p.mDexOptNeeded) {
                    continue;
                }
                if (pkgs == null) {
                    pkgs = new HashSet<String>();
                }
                pkgs.add(p.packageName);
            }
        }
        return pkgs;
    }

    public void shutdown() {
        mPackageUsage.write(true);
    }

    private void performDexOptLibsLI(ArrayList<String> libs, String instructionSet,
             boolean forceDex, boolean defer, HashSet<String> done) {
        for (int i=0; i<libs.size(); i++) {
            PackageParser.Package libPkg;
            String libName;
            synchronized (mPackages) {
                libName = libs.get(i);
                SharedLibraryEntry lib = mSharedLibraries.get(libName);
                if (lib != null && lib.apk != null) {
                    libPkg = mPackages.get(lib.apk);
                } else {
                    libPkg = null;
                }
            }
            if (libPkg != null && !done.contains(libName)) {
                performDexOptLI(libPkg, instructionSet, forceDex, defer, done);
            }
        }
    }

    static final int DEX_OPT_SKIPPED = 0;
    static final int DEX_OPT_PERFORMED = 1;
    static final int DEX_OPT_DEFERRED = 2;
    static final int DEX_OPT_FAILED = -1;

    private int performDexOptLI(PackageParser.Package pkg, String instructionSetOverride,
            boolean forceDex, boolean defer, HashSet<String> done) {
        final String instructionSet = instructionSetOverride != null ?
                instructionSetOverride : getAppInstructionSet(pkg.applicationInfo);
        final String dexCodeInstructionSet = getDexCodeInstructionSet(instructionSet);
        if (done != null) {
            done.add(pkg.packageName);
            if (pkg.usesLibraries != null) {
                performDexOptLibsLI(pkg.usesLibraries, dexCodeInstructionSet, forceDex, defer, done);
            }
            if (pkg.usesOptionalLibraries != null) {
                performDexOptLibsLI(pkg.usesOptionalLibraries, dexCodeInstructionSet, forceDex, defer, done);
            }
        }

        final boolean vmSafeMode = (pkg.applicationInfo.flags & ApplicationInfo.FLAG_VM_SAFE_MODE) != 0;
        boolean performed = false;
        if ((pkg.applicationInfo.flags&ApplicationInfo.FLAG_HAS_CODE) != 0) {
            String path = pkg.mScanPath;
            try {
                // This will return DEXOPT_NEEDED if we either cannot find any odex file for this
                // patckage or the one we find does not match the image checksum (i.e. it was
                // compiled against an old image). It will return PATCHOAT_NEEDED if we can find a
                // odex file and it matches the checksum of the image but not its base address,
                // meaning we need to move it.
                byte isDexOptNeededInternal = DexFile.isDexOptNeededInternal(path, pkg.packageName,
                                                                             dexCodeInstructionSet, defer);
                // There are three basic cases here:
                // 1.) we need to dexopt, either because we are forced or it is needed
                // 2.) we are defering a needed dexopt
                // 3.) we are skipping an unneeded dexopt
                if (forceDex || (!defer && isDexOptNeededInternal == DexFile.DEXOPT_NEEDED)) {
                    Log.i(TAG, "Running dexopt on: " + pkg.applicationInfo.packageName
                            + " vmSafeMode=" + vmSafeMode);
                    final int sharedGid = UserHandle.getSharedAppGid(pkg.applicationInfo.uid);
                    int ret = mInstaller.dexopt(path, sharedGid, !isForwardLocked(pkg),
                                                pkg.packageName, dexCodeInstructionSet, vmSafeMode);
                    // Note that we ran dexopt, since rerunning will
                    // probably just result in an error again.
                    pkg.mDexOptNeeded = false;
                    if (ret < 0) {
                        return DEX_OPT_FAILED;
                    }
                    return DEX_OPT_PERFORMED;
                } else if (!defer && isDexOptNeededInternal == DexFile.PATCHOAT_NEEDED) {
                    Log.i(TAG, "Running patchoat on: " + pkg.applicationInfo.packageName);
                    final int sharedGid = UserHandle.getSharedAppGid(pkg.applicationInfo.uid);
                    int ret = mInstaller.patchoat(path, sharedGid, !isForwardLocked(pkg),
                                                  pkg.packageName, instructionSet);
                    // Note that we ran patchoat, since rerunning will
                    // probably just result in an error again.
                    pkg.mDexOptNeeded = false;
                    if (ret < 0) {
                        return DEX_OPT_FAILED;
                    }
                    return DEX_OPT_PERFORMED;
                }
                if (defer && isDexOptNeededInternal != DexFile.UP_TO_DATE) {
                    if (mDeferredDexOpt == null) {
                        mDeferredDexOpt = new HashSet<PackageParser.Package>();
                    }
                    mDeferredDexOpt.add(pkg);
                    return DEX_OPT_DEFERRED;
                }
                pkg.mDexOptNeeded = false;
                return DEX_OPT_SKIPPED;
            } catch (FileNotFoundException e) {
                Slog.w(TAG, "Apk not found for dexopt: " + path);
                return DEX_OPT_FAILED;
            } catch (IOException e) {
                Slog.w(TAG, "IOException reading apk: " + path, e);
                return DEX_OPT_FAILED;
            } catch (StaleDexCacheError e) {
                Slog.w(TAG, "StaleDexCacheError when reading apk: " + path, e);
                return DEX_OPT_FAILED;
            } catch (Exception e) {
                Slog.w(TAG, "Exception when doing dexopt : ", e);
                return DEX_OPT_FAILED;
            }
        }
        return DEX_OPT_SKIPPED;
    }

    private String getAppInstructionSet(ApplicationInfo info) {
        String instructionSet = getPreferredInstructionSet();

        if (info.cpuAbi != null) {
            instructionSet = VMRuntime.getInstructionSet(info.cpuAbi);
        }

        return instructionSet;
    }

    private String getAppInstructionSetFromSettings(PackageSetting ps) {
        String instructionSet = getPreferredInstructionSet();

        if (ps.cpuAbiString != null) {
            instructionSet = VMRuntime.getInstructionSet(ps.cpuAbiString);
        }

        return instructionSet;
    }

    private static String getPreferredInstructionSet() {
        if (sPreferredInstructionSet == null) {
            sPreferredInstructionSet = VMRuntime.getInstructionSet(Build.SUPPORTED_ABIS[0]);
        }

        return sPreferredInstructionSet;
    }

    private static List<String> getAllInstructionSets() {
        final String[] allAbis = Build.SUPPORTED_ABIS;
        final List<String> allInstructionSets = new ArrayList<String>(allAbis.length);

        for (String abi : allAbis) {
            final String instructionSet = VMRuntime.getInstructionSet(abi);
            if (!allInstructionSets.contains(instructionSet)) {
                allInstructionSets.add(instructionSet);
            }
        }

        return allInstructionSets;
    }

    /**
     * Returns the instruction set that should be used to compile dex code. In the presence of
     * a native bridge this might be different than the one shared libraries use.
     */
    public static String getDexCodeInstructionSet(String sharedLibraryIsa) {
        String dexCodeIsa = SystemProperties.get("ro.dalvik.vm.isa." + sharedLibraryIsa);
        return (dexCodeIsa.isEmpty() ? sharedLibraryIsa : dexCodeIsa);
    }

    private static String[] getDexCodeInstructionSets(String[] instructionSets) {
        HashSet<String> dexCodeInstructionSets = new HashSet<String>(instructionSets.length);
        for (String instructionSet : instructionSets) {
            dexCodeInstructionSets.add(getDexCodeInstructionSet(instructionSet));
        }
        return dexCodeInstructionSets.toArray(new String[dexCodeInstructionSets.size()]);
    }

    private int performDexOptLI(PackageParser.Package pkg, boolean forceDex, boolean defer,
            boolean inclDependencies) {
        HashSet<String> done;
        if (inclDependencies && (pkg.usesLibraries != null || pkg.usesOptionalLibraries != null)) {
            done = new HashSet<String>();
            done.add(pkg.packageName);
        } else {
            done = null;
        }
        return performDexOptLI(pkg, null /* instruction set override */,  forceDex, defer, done);
    }

    private boolean verifyPackageUpdateLPr(PackageSetting oldPkg, PackageParser.Package newPkg) {
        if ((oldPkg.pkgFlags&ApplicationInfo.FLAG_SYSTEM) == 0) {
            Slog.w(TAG, "Unable to update from " + oldPkg.name
                    + " to " + newPkg.packageName
                    + ": old package not in system partition");
            return false;
        } else if (mPackages.get(oldPkg.name) != null) {
            Slog.w(TAG, "Unable to update from " + oldPkg.name
                    + " to " + newPkg.packageName
                    + ": old package still exists");
            return false;
        }
        return true;
    }

    File getDataPathForUser(int userId) {
        return new File(mUserAppDataDir.getAbsolutePath() + File.separator + userId);
    }

    private File getDataPathForPackage(String packageName, int userId) {
        /*
         * Until we fully support multiple users, return the directory we
         * previously would have. The PackageManagerTests will need to be
         * revised when this is changed back..
         */
        if (userId == 0) {
            return new File(mAppDataDir, packageName);
        } else {
            return new File(mUserAppDataDir.getAbsolutePath() + File.separator + userId
                + File.separator + packageName);
        }
    }

    private int createDataDirsLI(String packageName, int uid, String seinfo) {
        int[] users = sUserManager.getUserIds();
        int res = mInstaller.install(packageName, uid, uid, seinfo);
        if (res < 0) {
            return res;
        }
        for (int user : users) {
            if (user != 0) {
                res = mInstaller.createUserData(packageName,
                        UserHandle.getUid(user, uid), user, seinfo);
                if (res < 0) {
                    return res;
                }
            }
        }
        return res;
    }

    private int removeDataDirsLI(String packageName) {
        int[] users = sUserManager.getUserIds();
        int res = 0;
        for (int user : users) {
            int resInner = mInstaller.remove(packageName, user);
            if (resInner < 0) {
                res = resInner;
            }
        }

        final File nativeLibraryFile = new File(mAppLibInstallDir, packageName);
        NativeLibraryHelper.removeNativeBinariesFromDirLI(nativeLibraryFile);
        if (!nativeLibraryFile.delete()) {
            Slog.w(TAG, "Couldn't delete native library directory " + nativeLibraryFile.getPath());
        }

        return res;
    }

    private int addSharedLibraryLPw(final SharedLibraryEntry file, int num,
            PackageParser.Package changingLib) {
        if (file.path != null) {
            mTmpSharedLibraries[num] = file.path;
            return num+1;
        }
        PackageParser.Package p = mPackages.get(file.apk);
        if (changingLib != null && changingLib.packageName.equals(file.apk)) {
            // If we are doing this while in the middle of updating a library apk,
            // then we need to make sure to use that new apk for determining the
            // dependencies here.  (We haven't yet finished committing the new apk
            // to the package manager state.)
            if (p == null || p.packageName.equals(changingLib.packageName)) {
                p = changingLib;
            }
        }
        if (p != null) {
            String path = p.mPath;
            for (int i=0; i<num; i++) {
                if (mTmpSharedLibraries[i].equals(path)) {
                    return num;
                }
            }
            mTmpSharedLibraries[num] = p.mPath;
            return num+1;
        }
        return num;
    }

    private boolean updateSharedLibrariesLPw(PackageParser.Package pkg,
            PackageParser.Package changingLib) {
        // We might be upgrading from a version of the platform that did not
        // provide per-package native library directories for system apps.
        // Fix that up here.
        if (isSystemApp(pkg)) {
            PackageSetting ps = mSettings.mPackages.get(pkg.applicationInfo.packageName);
            setInternalAppNativeLibraryPath(pkg, ps);
        }

        if (pkg.usesLibraries != null || pkg.usesOptionalLibraries != null) {
            if (mTmpSharedLibraries == null ||
                    mTmpSharedLibraries.length < mSharedLibraries.size()) {
                mTmpSharedLibraries = new String[mSharedLibraries.size()];
            }
            int num = 0;
            int N = pkg.usesLibraries != null ? pkg.usesLibraries.size() : 0;
            for (int i=0; i<N; i++) {
                final SharedLibraryEntry file = mSharedLibraries.get(pkg.usesLibraries.get(i));
                if (file == null) {
                    Slog.e(TAG, "Package " + pkg.packageName
                            + " requires unavailable shared library "
                            + pkg.usesLibraries.get(i) + "; failing!");
                    mLastScanError = PackageManager.INSTALL_FAILED_MISSING_SHARED_LIBRARY;
                    return false;
                }
                num = addSharedLibraryLPw(file, num, changingLib);
            }
            N = pkg.usesOptionalLibraries != null ? pkg.usesOptionalLibraries.size() : 0;
            for (int i=0; i<N; i++) {
                final SharedLibraryEntry file = mSharedLibraries.get(pkg.usesOptionalLibraries.get(i));
                if (file == null) {
                    Slog.w(TAG, "Package " + pkg.packageName
                            + " desires unavailable shared library "
                            + pkg.usesOptionalLibraries.get(i) + "; ignoring!");
                } else {
                    num = addSharedLibraryLPw(file, num, changingLib);
                }
            }
            if (num > 0) {
                pkg.usesLibraryFiles = new String[num];
                System.arraycopy(mTmpSharedLibraries, 0,
                        pkg.usesLibraryFiles, 0, num);
            } else {
                pkg.usesLibraryFiles = null;
            }
        }
        return true;
    }

    private static boolean hasString(List<String> list, List<String> which) {
        if (list == null) {
            return false;
        }
        for (int i=list.size()-1; i>=0; i--) {
            for (int j=which.size()-1; j>=0; j--) {
                if (which.get(j).equals(list.get(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    private void updateAllSharedLibrariesLPw() {
        for (PackageParser.Package pkg : mPackages.values()) {
            updateSharedLibrariesLPw(pkg, null);
        }
    }

    private ArrayList<PackageParser.Package> updateAllSharedLibrariesLPw(
            PackageParser.Package changingPkg) {
        ArrayList<PackageParser.Package> res = null;
        for (PackageParser.Package pkg : mPackages.values()) {
            if (hasString(pkg.usesLibraries, changingPkg.libraryNames)
                    || hasString(pkg.usesOptionalLibraries, changingPkg.libraryNames)) {
                if (res == null) {
                    res = new ArrayList<PackageParser.Package>();
                }
                res.add(pkg);
                updateSharedLibrariesLPw(pkg, changingPkg);
            }
        }
        return res;
    }

    private PackageParser.Package scanPackageLI(PackageParser.Package pkg,
            int parseFlags, int scanMode, long currentTime, UserHandle user, String abiOverride) {
        File scanFile = new File(pkg.mScanPath);
        if (scanFile == null || pkg.applicationInfo.sourceDir == null ||
                pkg.applicationInfo.publicSourceDir == null) {
            // Bail out. The resource and code paths haven't been set.
            Slog.w(TAG, " Code and resource paths haven't been set correctly");
            mLastScanError = PackageManager.INSTALL_FAILED_INVALID_APK;
            return null;
        }

        if ((parseFlags&PackageParser.PARSE_IS_SYSTEM) != 0) {
            pkg.applicationInfo.flags |= ApplicationInfo.FLAG_SYSTEM;
        }

        if ((parseFlags&PackageParser.PARSE_IS_PRIVILEGED) != 0) {
            pkg.applicationInfo.flags |= ApplicationInfo.FLAG_PRIVILEGED;
        }

        if (mCustomResolverComponentName != null &&
                mCustomResolverComponentName.getPackageName().equals(pkg.packageName)) {
            setUpCustomResolverActivity(pkg);
        }

        if (pkg.packageName.equals("android")) {
            synchronized (mPackages) {
                if (mAndroidApplication != null) {
                    Slog.w(TAG, "*************************************************");
                    Slog.w(TAG, "Core android package being redefined.  Skipping.");
                    Slog.w(TAG, " file=" + scanFile);
                    Slog.w(TAG, "*************************************************");
                    mLastScanError = PackageManager.INSTALL_FAILED_DUPLICATE_PACKAGE;
                    return null;
                }

                // Set up information for our fall-back user intent resolution activity.
                mPlatformPackage = pkg;
                pkg.mVersionCode = mSdkVersion;
                mAndroidApplication = pkg.applicationInfo;

                if (!mResolverReplaced) {
                    mResolveActivity.applicationInfo = mAndroidApplication;
                    mResolveActivity.name = ResolverActivity.class.getName();
                    mResolveActivity.packageName = mAndroidApplication.packageName;
                    mResolveActivity.processName = "system:ui";
                    mResolveActivity.launchMode = ActivityInfo.LAUNCH_MULTIPLE;
                    mResolveActivity.flags = ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS;
                    mResolveActivity.theme = R.style.Theme_Holo_Dialog_Alert;
                    mResolveActivity.exported = true;
                    mResolveActivity.enabled = true;
                    mResolveInfo.activityInfo = mResolveActivity;
                    mResolveInfo.priority = 0;
                    mResolveInfo.preferredOrder = 0;
                    mResolveInfo.match = 0;
                    mResolveComponentName = new ComponentName(
                            mAndroidApplication.packageName, mResolveActivity.name);
                }
            }
        }

        if (DEBUG_PACKAGE_SCANNING) {
            if ((parseFlags & PackageParser.PARSE_CHATTY) != 0)
                Log.d(TAG, "Scanning package " + pkg.packageName);
        }

        if (mPackages.containsKey(pkg.packageName)
                || mSharedLibraries.containsKey(pkg.packageName)) {
            Slog.w(TAG, "Application package " + pkg.packageName
                    + " already installed.  Skipping duplicate.");
            mLastScanError = PackageManager.INSTALL_FAILED_DUPLICATE_PACKAGE;
            return null;
        }

        // Initialize package source and resource directories
        File destCodeFile = new File(pkg.applicationInfo.sourceDir);
        File destResourceFile = new File(pkg.applicationInfo.publicSourceDir);

        SharedUserSetting suid = null;
        PackageSetting pkgSetting = null;

        if (!isSystemApp(pkg)) {
            // Only system apps can use these features.
            pkg.mOriginalPackages = null;
            pkg.mRealPackage = null;
            pkg.mAdoptPermissions = null;
        }

        // writer
        synchronized (mPackages) {
            if (pkg.mSharedUserId != null) {
                suid = mSettings.getSharedUserLPw(pkg.mSharedUserId, 0, true);
                if (suid == null) {
                    Slog.w(TAG, "Creating application package " + pkg.packageName
                            + " for shared user failed");
                    mLastScanError = PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE;
                    return null;
                }
                if (DEBUG_PACKAGE_SCANNING) {
                    if ((parseFlags & PackageParser.PARSE_CHATTY) != 0)
                        Log.d(TAG, "Shared UserID " + pkg.mSharedUserId + " (uid=" + suid.userId
                                + "): packages=" + suid.packages);
                }
            }
            
            // Check if we are renaming from an original package name.
            PackageSetting origPackage = null;
            String realName = null;
            if (pkg.mOriginalPackages != null) {
                // This package may need to be renamed to a previously
                // installed name.  Let's check on that...
                final String renamed = mSettings.mRenamedPackages.get(pkg.mRealPackage);
                if (pkg.mOriginalPackages.contains(renamed)) {
                    // This package had originally been installed as the
                    // original name, and we have already taken care of
                    // transitioning to the new one.  Just update the new
                    // one to continue using the old name.
                    realName = pkg.mRealPackage;
                    if (!pkg.packageName.equals(renamed)) {
                        // Callers into this function may have already taken
                        // care of renaming the package; only do it here if
                        // it is not already done.
                        pkg.setPackageName(renamed);
                    }
                    
                } else {
                    for (int i=pkg.mOriginalPackages.size()-1; i>=0; i--) {
                        if ((origPackage = mSettings.peekPackageLPr(
                                pkg.mOriginalPackages.get(i))) != null) {
                            // We do have the package already installed under its
                            // original name...  should we use it?
                            if (!verifyPackageUpdateLPr(origPackage, pkg)) {
                                // New package is not compatible with original.
                                origPackage = null;
                                continue;
                            } else if (origPackage.sharedUser != null) {
                                // Make sure uid is compatible between packages.
                                if (!origPackage.sharedUser.name.equals(pkg.mSharedUserId)) {
                                    Slog.w(TAG, "Unable to migrate data from " + origPackage.name
                                            + " to " + pkg.packageName + ": old uid "
                                            + origPackage.sharedUser.name
                                            + " differs from " + pkg.mSharedUserId);
                                    origPackage = null;
                                    continue;
                                }
                            } else {
                                if (DEBUG_UPGRADE) Log.v(TAG, "Renaming new package "
                                        + pkg.packageName + " to old name " + origPackage.name);
                            }
                            break;
                        }
                    }
                }
            }
            
            if (mTransferedPackages.contains(pkg.packageName)) {
                Slog.w(TAG, "Package " + pkg.packageName
                        + " was transferred to another, but its .apk remains");
            }
            
            // Just create the setting, don't add it yet. For already existing packages
            // the PkgSetting exists already and doesn't have to be created.
            pkgSetting = mSettings.getPackageLPw(pkg, origPackage, realName, suid, destCodeFile,
                    destResourceFile, pkg.applicationInfo.nativeLibraryDir,
                    pkg.applicationInfo.cpuAbi,
                    pkg.applicationInfo.flags, user, false);
            if (pkgSetting == null) {
                Slog.w(TAG, "Creating application package " + pkg.packageName + " failed");
                mLastScanError = PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE;
                return null;
            }
            
            if (pkgSetting.origPackage != null) {
                // If we are first transitioning from an original package,
                // fix up the new package's name now.  We need to do this after
                // looking up the package under its new name, so getPackageLP
                // can take care of fiddling things correctly.
                pkg.setPackageName(origPackage.name);
                
                // File a report about this.
                String msg = "New package " + pkgSetting.realName
                        + " renamed to replace old package " + pkgSetting.name;
                reportSettingsProblem(Log.WARN, msg);
                
                // Make a note of it.
                mTransferedPackages.add(origPackage.name);
                
                // No longer need to retain this.
                pkgSetting.origPackage = null;
            }
            
            if (realName != null) {
                // Make a note of it.
                mTransferedPackages.add(pkg.packageName);
            }
            
            if (mSettings.isDisabledSystemPackageLPr(pkg.packageName)) {
                pkg.applicationInfo.flags |= ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
            }

            if ((parseFlags&PackageParser.PARSE_IS_SYSTEM_DIR) == 0) {
                // Check all shared libraries and map to their actual file path.
                // We only do this here for apps not on a system dir, because those
                // are the only ones that can fail an install due to this.  We
                // will take care of the system apps by updating all of their
                // library paths after the scan is done.
                if (!updateSharedLibrariesLPw(pkg, null)) {
                    return null;
                }
            }

            if (mFoundPolicyFile) {
                SELinuxMMAC.assignSeinfoValue(pkg);
            }

            pkg.applicationInfo.uid = pkgSetting.appId;
            pkg.mExtras = pkgSetting;

            if (!verifySignaturesLP(pkgSetting, pkg)) {
                if ((parseFlags&PackageParser.PARSE_IS_SYSTEM_DIR) == 0) {
                    return null;
                }
                // The signature has changed, but this package is in the system
                // image...  let's recover!
                pkgSetting.signatures.mSignatures = pkg.mSignatures;
                // However...  if this package is part of a shared user, but it
                // doesn't match the signature of the shared user, let's fail.
                // What this means is that you can't change the signatures
                // associated with an overall shared user, which doesn't seem all
                // that unreasonable.
                if (pkgSetting.sharedUser != null) {
                    if (compareSignatures(pkgSetting.sharedUser.signatures.mSignatures,
                            pkg.mSignatures) != PackageManager.SIGNATURE_MATCH) {
                        Log.w(TAG, "Signature mismatch for shared user : " + pkgSetting.sharedUser);
                        mLastScanError = PackageManager.INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES;
                        return null;
                    }
                }
                // File a report about this.
                String msg = "System package " + pkg.packageName
                        + " signature changed; retaining data.";
                reportSettingsProblem(Log.WARN, msg);
            }

            // Verify that this new package doesn't have any content providers
            // that conflict with existing packages.  Only do this if the
            // package isn't already installed, since we don't want to break
            // things that are installed.
            if ((scanMode&SCAN_NEW_INSTALL) != 0) {
                final int N = pkg.providers.size();
                int i;
                for (i=0; i<N; i++) {
                    PackageParser.Provider p = pkg.providers.get(i);
                    if (p.info.authority != null) {
                        String names[] = p.info.authority.split(";");
                        for (int j = 0; j < names.length; j++) {
                            if (mProvidersByAuthority.containsKey(names[j])) {
                                PackageParser.Provider other = mProvidersByAuthority.get(names[j]);
                                Slog.w(TAG, "Can't install because provider name " + names[j] +
                                        " (in package " + pkg.applicationInfo.packageName +
                                        ") is already used by "
                                        + ((other != null && other.getComponentName() != null)
                                                ? other.getComponentName().getPackageName() : "?"));
                                mLastScanError = PackageManager.INSTALL_FAILED_CONFLICTING_PROVIDER;
                                return null;
                            }
                        }
                    }
                }
            }

            if (pkg.mAdoptPermissions != null) {
                // This package wants to adopt ownership of permissions from
                // another package.
                for (int i = pkg.mAdoptPermissions.size() - 1; i >= 0; i--) {
                    final String origName = pkg.mAdoptPermissions.get(i);
                    final PackageSetting orig = mSettings.peekPackageLPr(origName);
                    if (orig != null) {
                        if (verifyPackageUpdateLPr(orig, pkg)) {
                            Slog.i(TAG, "Adopting permissions from " + origName + " to "
                                    + pkg.packageName);
                            mSettings.transferPermissionsLPw(origName, pkg.packageName);
                        }
                    }
                }
            }
        }

        final String pkgName = pkg.packageName;
        
        final long scanFileTime = scanFile.lastModified();
        final boolean forceDex = (scanMode&SCAN_FORCE_DEX) != 0;
        pkg.applicationInfo.processName = fixProcessName(
                pkg.applicationInfo.packageName,
                pkg.applicationInfo.processName,
                pkg.applicationInfo.uid);

        File dataPath;
        if (mPlatformPackage == pkg) {
            // The system package is special.
            dataPath = new File (Environment.getDataDirectory(), "system");
            pkg.applicationInfo.dataDir = dataPath.getPath();
        } else {
            // This is a normal package, need to make its data directory.
            dataPath = getDataPathForPackage(pkg.packageName, 0);

            boolean uidError = false;

            if (dataPath.exists()) {
                int currentUid = 0;
                try {
                    StructStat stat = Os.stat(dataPath.getPath());
                    currentUid = stat.st_uid;
                } catch (ErrnoException e) {
                    Slog.e(TAG, "Couldn't stat path " + dataPath.getPath(), e);
                }

                // If we have mismatched owners for the data path, we have a problem.
                if (currentUid != pkg.applicationInfo.uid) {
                    boolean recovered = false;
                    if (currentUid == 0) {
                        // The directory somehow became owned by root.  Wow.
                        // This is probably because the system was stopped while
                        // installd was in the middle of messing with its libs
                        // directory.  Ask installd to fix that.
                        int ret = mInstaller.fixUid(pkgName, pkg.applicationInfo.uid,
                                pkg.applicationInfo.uid);
                        if (ret >= 0) {
                            recovered = true;
                            String msg = "Package " + pkg.packageName
                                    + " unexpectedly changed to uid 0; recovered to " +
                                    + pkg.applicationInfo.uid;
                            reportSettingsProblem(Log.WARN, msg);
                        }
                    }
                    if (!recovered && ((parseFlags&PackageParser.PARSE_IS_SYSTEM) != 0
                            || (scanMode&SCAN_BOOTING) != 0)) {
                        // If this is a system app, we can at least delete its
                        // current data so the application will still work.
                        int ret = removeDataDirsLI(pkgName);
                        if (ret >= 0) {
                            // TODO: Kill the processes first
                            // Old data gone!
                            String prefix = (parseFlags&PackageParser.PARSE_IS_SYSTEM) != 0
                                    ? "System package " : "Third party package ";
                            String msg = prefix + pkg.packageName
                                    + " has changed from uid: "
                                    + currentUid + " to "
                                    + pkg.applicationInfo.uid + "; old data erased";
                            reportSettingsProblem(Log.WARN, msg);
                            recovered = true;

                            // And now re-install the app.
                            ret = createDataDirsLI(pkgName, pkg.applicationInfo.uid,
                                                   pkg.applicationInfo.seinfo);
                            if (ret == -1) {
                                // Ack should not happen!
                                msg = prefix + pkg.packageName
                                        + " could not have data directory re-created after delete.";
                                reportSettingsProblem(Log.WARN, msg);
                                mLastScanError = PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE;
                                return null;
                            }
                        }
                        if (!recovered) {
                            mHasSystemUidErrors = true;
                        }
                    } else if (!recovered) {
                        // If we allow this install to proceed, we will be broken.
                        // Abort, abort!
                        mLastScanError = PackageManager.INSTALL_FAILED_UID_CHANGED;
                        return null;
                    }
                    if (!recovered) {
                        pkg.applicationInfo.dataDir = "/mismatched_uid/settings_"
                            + pkg.applicationInfo.uid + "/fs_"
                            + currentUid;
                        pkg.applicationInfo.nativeLibraryDir = pkg.applicationInfo.dataDir;
                        String msg = "Package " + pkg.packageName
                                + " has mismatched uid: "
                                + currentUid + " on disk, "
                                + pkg.applicationInfo.uid + " in settings";
                        // writer
                        synchronized (mPackages) {
                            mSettings.mReadMessages.append(msg);
                            mSettings.mReadMessages.append('\n');
                            uidError = true;
                            if (!pkgSetting.uidError) {
                                reportSettingsProblem(Log.ERROR, msg);
                            }
                        }
                    }
                }
                pkg.applicationInfo.dataDir = dataPath.getPath();
                if (mShouldRestoreconData) {
                    Slog.i(TAG, "SELinux relabeling of " + pkg.packageName + " issued.");
                    mInstaller.restoreconData(pkg.packageName, pkg.applicationInfo.seinfo,
                                pkg.applicationInfo.uid);
                }
            } else {
                if (DEBUG_PACKAGE_SCANNING) {
                    if ((parseFlags & PackageParser.PARSE_CHATTY) != 0)
                        Log.v(TAG, "Want this data dir: " + dataPath);
                }
                //invoke installer to do the actual installation
                int ret = createDataDirsLI(pkgName, pkg.applicationInfo.uid,
                                           pkg.applicationInfo.seinfo);
                if (ret < 0) {
                    // Error from installer
                    mLastScanError = PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE;
                    return null;
                }

                if (dataPath.exists()) {
                    pkg.applicationInfo.dataDir = dataPath.getPath();
                } else {
                    Slog.w(TAG, "Unable to create data directory: " + dataPath);
                    pkg.applicationInfo.dataDir = null;
                }
            }

            /*
             * Set the data dir to the default "/data/data/<package name>/lib"
             * if we got here without anyone telling us different (e.g., apps
             * stored on SD card have their native libraries stored in the ASEC
             * container with the APK).
             *
             * This happens during an upgrade from a package settings file that
             * doesn't have a native library path attribute at all.
             */
            if (pkg.applicationInfo.nativeLibraryDir == null && pkg.applicationInfo.dataDir != null) {
                if (pkgSetting.nativeLibraryPathString == null) {
                    setInternalAppNativeLibraryPath(pkg, pkgSetting);
                } else {
                    pkg.applicationInfo.nativeLibraryDir = pkgSetting.nativeLibraryPathString;
                }
            }
            pkgSetting.uidError = uidError;
        }

        String path = scanFile.getPath();
        /* Note: We don't want to unpack the native binaries for
         *        system applications, unless they have been updated
         *        (the binaries are already under /system/lib).
         *        Also, don't unpack libs for apps on the external card
         *        since they should have their libraries in the ASEC
         *        container already.
         *
         *        In other words, we're going to unpack the binaries
         *        only for non-system apps and system app upgrades.
         */
        if (pkg.applicationInfo.nativeLibraryDir != null) {
            ApkHandle handle = null;
            try {
                handle = ApkHandle.create(scanFile.getPath());
                // Enable gross and lame hacks for apps that are built with old
                // SDK tools. We must scan their APKs for renderscript bitcode and
                // not launch them if it's present. Don't bother checking on devices
                // that don't have 64 bit support.
                String[] abiList = Build.SUPPORTED_ABIS;
                boolean hasLegacyRenderscriptBitcode = false;
                if (abiOverride != null) {
                    abiList = new String[] { abiOverride };
                } else if (Build.SUPPORTED_64_BIT_ABIS.length > 0 &&
                        NativeLibraryHelper.hasRenderscriptBitcode(handle)) {
                    abiList = Build.SUPPORTED_32_BIT_ABIS;
                    hasLegacyRenderscriptBitcode = true;
                }

                File nativeLibraryDir = new File(pkg.applicationInfo.nativeLibraryDir);
                final String dataPathString = dataPath.getCanonicalPath();

                if (isSystemApp(pkg) && !isUpdatedSystemApp(pkg)) {
                    /*
                     * Upgrading from a previous version of the OS sometimes
                     * leaves native libraries in the /data/data/<app>/lib
                     * directory for system apps even when they shouldn't be.
                     * Recent changes in the JNI library search path
                     * necessitates we remove those to match previous behavior.
                     */
                    if (NativeLibraryHelper.removeNativeBinariesFromDirLI(nativeLibraryDir)) {
                        Log.i(TAG, "removed obsolete native libraries for system package "
                                + path);
                    }
                    if (abiOverride != null || hasLegacyRenderscriptBitcode) {
                        pkg.applicationInfo.cpuAbi = abiList[0];
                        pkgSetting.cpuAbiString = abiList[0];
                    } else {
                        setInternalAppAbi(pkg, pkgSetting);
                    }
                } else {
                    if (!isForwardLocked(pkg) && !isExternal(pkg)) {
                        /*
                        * Update native library dir if it starts with
                        * /data/data
                        */
                        if (nativeLibraryDir.getPath().startsWith(dataPathString)) {
                            setInternalAppNativeLibraryPath(pkg, pkgSetting);
                            nativeLibraryDir = new File(pkg.applicationInfo.nativeLibraryDir);
                        }

                        try {
                            int copyRet = copyNativeLibrariesForInternalApp(handle,
                                    nativeLibraryDir, abiList);
                            if (copyRet < 0 && copyRet != PackageManager.NO_NATIVE_LIBRARIES) {
                                Slog.e(TAG, "Unable to copy native libraries");
                                mLastScanError = PackageManager.INSTALL_FAILED_INTERNAL_ERROR;
                                return null;
                            }

                            // We've successfully copied native libraries across, so we make a
                            // note of what ABI we're using
                            if (copyRet != PackageManager.NO_NATIVE_LIBRARIES) {
                                pkg.applicationInfo.cpuAbi = abiList[copyRet];
                            } else if (abiOverride != null || hasLegacyRenderscriptBitcode) {
                                pkg.applicationInfo.cpuAbi = abiList[0];
                            } else {
                                pkg.applicationInfo.cpuAbi = null;
                            }
                        } catch (IOException e) {
                            Slog.e(TAG, "Unable to copy native libraries", e);
                            mLastScanError = PackageManager.INSTALL_FAILED_INTERNAL_ERROR;
                            return null;
                        }
                    } else {
                        // We don't have to copy the shared libraries if we're in the ASEC container
                        // but we still need to scan the file to figure out what ABI the app needs.
                        //
                        // TODO: This duplicates work done in the default container service. It's possible
                        // to clean this up but we'll need to change the interface between this service
                        // and IMediaContainerService (but doing so will spread this logic out, rather
                        // than centralizing it).
                        final int abi = NativeLibraryHelper.findSupportedAbi(handle, abiList);
                        if (abi >= 0) {
                            pkg.applicationInfo.cpuAbi = abiList[abi];
                        } else if (abi == PackageManager.NO_NATIVE_LIBRARIES) {
                            // Note that (non upgraded) system apps will not have any native
                            // libraries bundled in their APK, but we're guaranteed not to be
                            // such an app at this point.
                            if (abiOverride != null || hasLegacyRenderscriptBitcode) {
                                pkg.applicationInfo.cpuAbi = abiList[0];
                            } else {
                                pkg.applicationInfo.cpuAbi = null;
                            }
                        } else {
                            mLastScanError = PackageManager.INSTALL_FAILED_INTERNAL_ERROR;
                            return null;
                        }
                    }

                    if (DEBUG_INSTALL) Slog.i(TAG, "Linking native library dir for " + path);
                    final int[] userIds = sUserManager.getUserIds();
                    synchronized (mInstallLock) {
                        for (int userId : userIds) {
                            if (mInstaller.linkNativeLibraryDirectory(pkg.packageName,
                                    pkg.applicationInfo.nativeLibraryDir, userId) < 0) {
                                Slog.w(TAG, "Failed linking native library dir (user=" + userId
                                        + ")");
                                mLastScanError = PackageManager.INSTALL_FAILED_INTERNAL_ERROR;
                                return null;
                            }
                        }
                    }
                }

                pkgSetting.cpuAbiString = pkg.applicationInfo.cpuAbi;
            } catch (IOException ioe) {
                Slog.e(TAG, "Unable to get canonical file " + ioe.toString());
            } finally {
                IoUtils.closeQuietly(handle);
            }
        }
        pkg.mScanPath = path;

        if ((scanMode&SCAN_BOOTING) == 0 && pkgSetting.sharedUser != null) {
            // We don't do this here during boot because we can do it all
            // at once after scanning all existing packages.
            //
            // We also do this *before* we perform dexopt on this package, so that
            // we can avoid redundant dexopts, and also to make sure we've got the
            // code and package path correct.
            if (!adjustCpuAbisForSharedUserLPw(pkgSetting.sharedUser.packages,
                    pkg, forceDex, (scanMode & SCAN_DEFER_DEX) != 0)) {
                mLastScanError = PackageManager.INSTALL_FAILED_CPU_ABI_INCOMPATIBLE;
                return null;
            }
        }

        if ((scanMode&SCAN_NO_DEX) == 0) {
            if (performDexOptLI(pkg, forceDex, (scanMode&SCAN_DEFER_DEX) != 0, false)
                    == DEX_OPT_FAILED) {
                if ((scanMode & SCAN_DELETE_DATA_ON_FAILURES) != 0) {
                    removeDataDirsLI(pkg.packageName);
                }

                mLastScanError = PackageManager.INSTALL_FAILED_DEXOPT;
                return null;
            }
        }

        if (mFactoryTest && pkg.requestedPermissions.contains(
                android.Manifest.permission.FACTORY_TEST)) {
            pkg.applicationInfo.flags |= ApplicationInfo.FLAG_FACTORY_TEST;
        }

        ArrayList<PackageParser.Package> clientLibPkgs = null;

        // writer
        synchronized (mPackages) {
            if ((pkg.applicationInfo.flags&ApplicationInfo.FLAG_SYSTEM) != 0) {
                // Only system apps can add new shared libraries.
                if (pkg.libraryNames != null) {
                    for (int i=0; i<pkg.libraryNames.size(); i++) {
                        String name = pkg.libraryNames.get(i);
                        boolean allowed = false;
                        if (isUpdatedSystemApp(pkg)) {
                            // New library entries can only be added through the
                            // system image.  This is important to get rid of a lot
                            // of nasty edge cases: for example if we allowed a non-
                            // system update of the app to add a library, then uninstalling
                            // the update would make the library go away, and assumptions
                            // we made such as through app install filtering would now
                            // have allowed apps on the device which aren't compatible
                            // with it.  Better to just have the restriction here, be
                            // conservative, and create many fewer cases that can negatively
                            // impact the user experience.
                            final PackageSetting sysPs = mSettings
                                    .getDisabledSystemPkgLPr(pkg.packageName);
                            if (sysPs.pkg != null && sysPs.pkg.libraryNames != null) {
                                for (int j=0; j<sysPs.pkg.libraryNames.size(); j++) {
                                    if (name.equals(sysPs.pkg.libraryNames.get(j))) {
                                        allowed = true;
                                        allowed = true;
                                        break;
                                    }
                                }
                            }
                        } else {
                            allowed = true;
                        }
                        if (allowed) {
                            if (!mSharedLibraries.containsKey(name)) {
                                mSharedLibraries.put(name, new SharedLibraryEntry(null, pkg.packageName));
                            } else if (!name.equals(pkg.packageName)) {
                                Slog.w(TAG, "Package " + pkg.packageName + " library "
                                        + name + " already exists; skipping");
                            }
                        } else {
                            Slog.w(TAG, "Package " + pkg.packageName + " declares lib "
                                    + name + " that is not declared on system image; skipping");
                        }
                    }
                    if ((scanMode&SCAN_BOOTING) == 0) {
                        // If we are not booting, we need to update any applications
                        // that are clients of our shared library.  If we are booting,
                        // this will all be done once the scan is complete.
                        clientLibPkgs = updateAllSharedLibrariesLPw(pkg);
                    }
                }
            }
        }

        // We also need to dexopt any apps that are dependent on this library.  Note that
        // if these fail, we should abort the install since installing the library will
        // result in some apps being broken.
        if (clientLibPkgs != null) {
            if ((scanMode&SCAN_NO_DEX) == 0) {
                for (int i=0; i<clientLibPkgs.size(); i++) {
                    PackageParser.Package clientPkg = clientLibPkgs.get(i);
                    if (performDexOptLI(clientPkg, forceDex, (scanMode&SCAN_DEFER_DEX) != 0, false)
                            == DEX_OPT_FAILED) {
                        if ((scanMode & SCAN_DELETE_DATA_ON_FAILURES) != 0) {
                            removeDataDirsLI(pkg.packageName);
                        }

                        mLastScanError = PackageManager.INSTALL_FAILED_DEXOPT;
                        return null;
                    }
                }
            }
        }

        // Request the ActivityManager to kill the process(only for existing packages)
        // so that we do not end up in a confused state while the user is still using the older
        // version of the application while the new one gets installed.
        if ((parseFlags & PackageManager.INSTALL_REPLACE_EXISTING) != 0) {
            // If the package lives in an asec, tell everyone that the container is going
            // away so they can clean up any references to its resources (which would prevent
            // vold from being able to unmount the asec)
            if (isForwardLocked(pkg) || isExternal(pkg)) {
                if (DEBUG_INSTALL) {
                    Slog.i(TAG, "upgrading pkg " + pkg + " is ASEC-hosted -> UNAVAILABLE");
                }
                final int[] uidArray = new int[] { pkg.applicationInfo.uid };
                final ArrayList<String> pkgList = new ArrayList<String>(1);
                pkgList.add(pkg.applicationInfo.packageName);
                sendResourcesChangedBroadcast(false, true, pkgList, uidArray, null);
            }

            // Post the request that it be killed now that the going-away broadcast is en route
            killApplication(pkg.applicationInfo.packageName,
                        pkg.applicationInfo.uid, "update pkg");
        }

        // Also need to kill any apps that are dependent on the library.
        if (clientLibPkgs != null) {
            for (int i=0; i<clientLibPkgs.size(); i++) {
                PackageParser.Package clientPkg = clientLibPkgs.get(i);
                killApplication(clientPkg.applicationInfo.packageName,
                        clientPkg.applicationInfo.uid, "update lib");
            }
        }

        // writer
        synchronized (mPackages) {
            // We don't expect installation to fail beyond this point,
            if ((scanMode&SCAN_MONITOR) != 0) {
                mAppDirs.put(pkg.mPath, pkg);
            }
            // Add the new setting to mSettings
            mSettings.insertPackageSettingLPw(pkgSetting, pkg);
            // Add the new setting to mPackages
            mPackages.put(pkg.applicationInfo.packageName, pkg);
            // Make sure we don't accidentally delete its data.
            final Iterator<PackageCleanItem> iter = mSettings.mPackagesToBeCleaned.iterator();
            while (iter.hasNext()) {
                PackageCleanItem item = iter.next();
                if (pkgName.equals(item.packageName)) {
                    iter.remove();
                }
            }

            // Take care of first install / last update times.
            if (currentTime != 0) {
                if (pkgSetting.firstInstallTime == 0) {
                    pkgSetting.firstInstallTime = pkgSetting.lastUpdateTime = currentTime;
                } else if ((scanMode&SCAN_UPDATE_TIME) != 0) {
                    pkgSetting.lastUpdateTime = currentTime;
                }
            } else if (pkgSetting.firstInstallTime == 0) {
                // We need *something*.  Take time time stamp of the file.
                pkgSetting.firstInstallTime = pkgSetting.lastUpdateTime = scanFileTime;
            } else if ((parseFlags&PackageParser.PARSE_IS_SYSTEM_DIR) != 0) {
                if (scanFileTime != pkgSetting.timeStamp) {
                    // A package on the system image has changed; consider this
                    // to be an update.
                    pkgSetting.lastUpdateTime = scanFileTime;
                }
            }

            // Add the package's KeySets to the global KeySetManager
            KeySetManager ksm = mSettings.mKeySetManager;
            try {
                ksm.addSigningKeySetToPackage(pkg.packageName, pkg.mSigningKeys);
                if (pkg.mKeySetMapping != null) {
                    for (Map.Entry<String, Set<PublicKey>> entry : pkg.mKeySetMapping.entrySet()) {
                        if (entry.getValue() != null) {
                            ksm.addDefinedKeySetToPackage(pkg.packageName,
                                entry.getValue(), entry.getKey());
                        }
                    }
                }
            } catch (NullPointerException e) {
                Slog.e(TAG, "Could not add KeySet to " + pkg.packageName, e);
            } catch (IllegalArgumentException e) {
                Slog.e(TAG, "Could not add KeySet to malformed package" + pkg.packageName, e);
            }

            int N = pkg.providers.size();
            StringBuilder r = null;
            int i;
            for (i=0; i<N; i++) {
                PackageParser.Provider p = pkg.providers.get(i);
                p.info.processName = fixProcessName(pkg.applicationInfo.processName,
                        p.info.processName, pkg.applicationInfo.uid);
                mProviders.addProvider(p);
                p.syncable = p.info.isSyncable;
                if (p.info.authority != null) {
                    String names[] = p.info.authority.split(";");
                    p.info.authority = null;
                    for (int j = 0; j < names.length; j++) {
                        if (j == 1 && p.syncable) {
                            // We only want the first authority for a provider to possibly be
                            // syncable, so if we already added this provider using a different
                            // authority clear the syncable flag. We copy the provider before
                            // changing it because the mProviders object contains a reference
                            // to a provider that we don't want to change.
                            // Only do this for the second authority since the resulting provider
                            // object can be the same for all future authorities for this provider.
                            p = new PackageParser.Provider(p);
                            p.syncable = false;
                        }
                        if (!mProvidersByAuthority.containsKey(names[j])) {
                            mProvidersByAuthority.put(names[j], p);
                            if (p.info.authority == null) {
                                p.info.authority = names[j];
                            } else {
                                p.info.authority = p.info.authority + ";" + names[j];
                            }
                            if (DEBUG_PACKAGE_SCANNING) {
                                if ((parseFlags & PackageParser.PARSE_CHATTY) != 0)
                                    Log.d(TAG, "Registered content provider: " + names[j]
                                            + ", className = " + p.info.name + ", isSyncable = "
                                            + p.info.isSyncable);
                            }
                        } else {
                            PackageParser.Provider other = mProvidersByAuthority.get(names[j]);
                            Slog.w(TAG, "Skipping provider name " + names[j] +
                                    " (in package " + pkg.applicationInfo.packageName +
                                    "): name already used by "
                                    + ((other != null && other.getComponentName() != null)
                                            ? other.getComponentName().getPackageName() : "?"));
                        }
                    }
                }
                if ((parseFlags&PackageParser.PARSE_CHATTY) != 0) {
                    if (r == null) {
                        r = new StringBuilder(256);
                    } else {
                        r.append(' ');
                    }
                    r.append(p.info.name);
                }
            }
            if (r != null) {
                if (DEBUG_PACKAGE_SCANNING) Log.d(TAG, "  Providers: " + r);
            }

            N = pkg.services.size();
            r = null;
            for (i=0; i<N; i++) {
                PackageParser.Service s = pkg.services.get(i);
                s.info.processName = fixProcessName(pkg.applicationInfo.processName,
                        s.info.processName, pkg.applicationInfo.uid);
                mServices.addService(s);
                if ((parseFlags&PackageParser.PARSE_CHATTY) != 0) {
                    if (r == null) {
                        r = new StringBuilder(256);
                    } else {
                        r.append(' ');
                    }
                    r.append(s.info.name);
                }
            }
            if (r != null) {
                if (DEBUG_PACKAGE_SCANNING) Log.d(TAG, "  Services: " + r);
            }

            N = pkg.receivers.size();
            r = null;
            for (i=0; i<N; i++) {
                PackageParser.Activity a = pkg.receivers.get(i);
                a.info.processName = fixProcessName(pkg.applicationInfo.processName,
                        a.info.processName, pkg.applicationInfo.uid);
                mReceivers.addActivity(a, "receiver");
                if ((parseFlags&PackageParser.PARSE_CHATTY) != 0) {
                    if (r == null) {
                        r = new StringBuilder(256);
                    } else {
                        r.append(' ');
                    }
                    r.append(a.info.name);
                }
            }
            if (r != null) {
                if (DEBUG_PACKAGE_SCANNING) Log.d(TAG, "  Receivers: " + r);
            }

            N = pkg.activities.size();
            r = null;
            for (i=0; i<N; i++) {
                PackageParser.Activity a = pkg.activities.get(i);
                a.info.processName = fixProcessName(pkg.applicationInfo.processName,
                        a.info.processName, pkg.applicationInfo.uid);
                mActivities.addActivity(a, "activity");
                if ((parseFlags&PackageParser.PARSE_CHATTY) != 0) {
                    if (r == null) {
                        r = new StringBuilder(256);
                    } else {
                        r.append(' ');
                    }
                    r.append(a.info.name);
                }
            }
            if (r != null) {
                if (DEBUG_PACKAGE_SCANNING) Log.d(TAG, "  Activities: " + r);
            }

            N = pkg.permissionGroups.size();
            r = null;
            for (i=0; i<N; i++) {
                PackageParser.PermissionGroup pg = pkg.permissionGroups.get(i);
                PackageParser.PermissionGroup cur = mPermissionGroups.get(pg.info.name);
                if (cur == null) {
                    mPermissionGroups.put(pg.info.name, pg);
                    if ((parseFlags&PackageParser.PARSE_CHATTY) != 0) {
                        if (r == null) {
                            r = new StringBuilder(256);
                        } else {
                            r.append(' ');
                        }
                        r.append(pg.info.name);
                    }
                } else {
                    Slog.w(TAG, "Permission group " + pg.info.name + " from package "
                            + pg.info.packageName + " ignored: original from "
                            + cur.info.packageName);
                    if ((parseFlags&PackageParser.PARSE_CHATTY) != 0) {
                        if (r == null) {
                            r = new StringBuilder(256);
                        } else {
                            r.append(' ');
                        }
                        r.append("DUP:");
                        r.append(pg.info.name);
                    }
                }
            }
            if (r != null) {
                if (DEBUG_PACKAGE_SCANNING) Log.d(TAG, "  Permission Groups: " + r);
            }

            N = pkg.permissions.size();
            r = null;
            for (i=0; i<N; i++) {
                PackageParser.Permission p = pkg.permissions.get(i);
                HashMap<String, BasePermission> permissionMap =
                        p.tree ? mSettings.mPermissionTrees
                        : mSettings.mPermissions;
                p.group = mPermissionGroups.get(p.info.group);
                if (p.info.group == null || p.group != null) {
                    BasePermission bp = permissionMap.get(p.info.name);
                    if (bp == null) {
                        bp = new BasePermission(p.info.name, p.info.packageName,
                                BasePermission.TYPE_NORMAL);
                        permissionMap.put(p.info.name, bp);
                    }
                    if (bp.perm == null) {
                        if (bp.sourcePackage != null
                                && !bp.sourcePackage.equals(p.info.packageName)) {
                            // If this is a permission that was formerly defined by a non-system
                            // app, but is now defined by a system app (following an upgrade),
                            // discard the previous declaration and consider the system's to be
                            // canonical.
                            if (isSystemApp(p.owner)) {
                                Slog.i(TAG, "New decl " + p.owner + " of permission  "
                                        + p.info.name + " is system");
                                bp.sourcePackage = null;
                            }
                        }
                        if (bp.sourcePackage == null
                                || bp.sourcePackage.equals(p.info.packageName)) {
                            BasePermission tree = findPermissionTreeLP(p.info.name);
                            if (tree == null
                                    || tree.sourcePackage.equals(p.info.packageName)) {
                                bp.packageSetting = pkgSetting;
                                bp.perm = p;
                                bp.uid = pkg.applicationInfo.uid;
                                if ((parseFlags&PackageParser.PARSE_CHATTY) != 0) {
                                    if (r == null) {
                                        r = new StringBuilder(256);
                                    } else {
                                        r.append(' ');
                                    }
                                    r.append(p.info.name);
                                }
                            } else {
                                Slog.w(TAG, "Permission " + p.info.name + " from package "
                                        + p.info.packageName + " ignored: base tree "
                                        + tree.name + " is from package "
                                        + tree.sourcePackage);
                            }
                        } else {
                            Slog.w(TAG, "Permission " + p.info.name + " from package "
                                    + p.info.packageName + " ignored: original from "
                                    + bp.sourcePackage);
                        }
                    } else if ((parseFlags&PackageParser.PARSE_CHATTY) != 0) {
                        if (r == null) {
                            r = new StringBuilder(256);
                        } else {
                            r.append(' ');
                        }
                        r.append("DUP:");
                        r.append(p.info.name);
                    }
                    if (bp.perm == p) {
                        bp.protectionLevel = p.info.protectionLevel;
                    }
                } else {
                    Slog.w(TAG, "Permission " + p.info.name + " from package "
                            + p.info.packageName + " ignored: no group "
                            + p.group);
                }
            }
            if (r != null) {
                if (DEBUG_PACKAGE_SCANNING) Log.d(TAG, "  Permissions: " + r);
            }

            N = pkg.instrumentation.size();
            r = null;
            for (i=0; i<N; i++) {
                PackageParser.Instrumentation a = pkg.instrumentation.get(i);
                a.info.packageName = pkg.applicationInfo.packageName;
                a.info.sourceDir = pkg.applicationInfo.sourceDir;
                a.info.publicSourceDir = pkg.applicationInfo.publicSourceDir;
                a.info.dataDir = pkg.applicationInfo.dataDir;
                a.info.nativeLibraryDir = pkg.applicationInfo.nativeLibraryDir;
                mInstrumentation.put(a.getComponentName(), a);
                if ((parseFlags&PackageParser.PARSE_CHATTY) != 0) {
                    if (r == null) {
                        r = new StringBuilder(256);
                    } else {
                        r.append(' ');
                    }
                    r.append(a.info.name);
                }
            }
            if (r != null) {
                if (DEBUG_PACKAGE_SCANNING) Log.d(TAG, "  Instrumentation: " + r);
            }

            if (pkg.protectedBroadcasts != null) {
                N = pkg.protectedBroadcasts.size();
                for (i=0; i<N; i++) {
                    mProtectedBroadcasts.add(pkg.protectedBroadcasts.get(i));
                }
            }

            pkgSetting.setTimeStamp(scanFileTime);

            // Create idmap files for pairs of (packages, overlay packages).
            // Note: "android", ie framework-res.apk, is handled by native layers.
            if (pkg.mOverlayTarget != null) {
                // This is an overlay package.
                if (pkg.mOverlayTarget != null && !pkg.mOverlayTarget.equals("android")) {
                    if (!mOverlays.containsKey(pkg.mOverlayTarget)) {
                        mOverlays.put(pkg.mOverlayTarget,
                                new HashMap<String, PackageParser.Package>());
                    }
                    HashMap<String, PackageParser.Package> map = mOverlays.get(pkg.mOverlayTarget);
                    map.put(pkg.packageName, pkg);
                    PackageParser.Package orig = mPackages.get(pkg.mOverlayTarget);
                    if (orig != null && !createIdmapForPackagePairLI(orig, pkg)) {
                        mLastScanError = PackageManager.INSTALL_FAILED_UPDATE_INCOMPATIBLE;
                        return null;
                    }
                }
            } else if (mOverlays.containsKey(pkg.packageName) &&
                    !pkg.packageName.equals("android")) {
                // This is a regular package, with one or more known overlay packages.
                createIdmapsForPackageLI(pkg);
            }
        }

        return pkg;
    }

    /**
     * Adjusts ABIs for a set of packages belonging to a shared user so that they all match.
     * i.e, so that all packages can be run inside a single process if required.
     *
     * Optionally, callers can pass in a parsed package via {@code newPackage} in which case
     * this function will either try and make the ABI for all packages in {@code packagesForUser}
     * match {@code scannedPackage} or will update the ABI of {@code scannedPackage} to match
     * the ABI selected for {@code packagesForUser}. This variant is used when installing or
     * updating a package that belongs to a shared user.
     */
    private boolean adjustCpuAbisForSharedUserLPw(Set<PackageSetting> packagesForUser,
            PackageParser.Package scannedPackage, boolean forceDexOpt, boolean deferDexOpt) {
        String requiredInstructionSet = null;
        if (scannedPackage != null && scannedPackage.applicationInfo.cpuAbi != null) {
            requiredInstructionSet = VMRuntime.getInstructionSet(
                     scannedPackage.applicationInfo.cpuAbi);
        }

        PackageSetting requirer = null;
        for (PackageSetting ps : packagesForUser) {
            // If packagesForUser contains scannedPackage, we skip it. This will happen
            // when scannedPackage is an update of an existing package. Without this check,
            // we will never be able to change the ABI of any package belonging to a shared
            // user, even if it's compatible with other packages.
            if (scannedPackage == null || ! scannedPackage.packageName.equals(ps.name)) {
                if (ps.cpuAbiString == null) {
                    continue;
                }

                final String instructionSet = VMRuntime.getInstructionSet(ps.cpuAbiString);
                if (requiredInstructionSet != null) {
                    if (!instructionSet.equals(requiredInstructionSet)) {
                        // We have a mismatch between instruction sets (say arm vs arm64).
                        // bail out.
                        String errorMessage = "Instruction set mismatch, "
                                + ((requirer == null) ? "[caller]" : requirer)
                                + " requires " + requiredInstructionSet + " whereas " + ps
                                + " requires " + instructionSet;
                        Slog.e(TAG, errorMessage);

                        reportSettingsProblem(Log.WARN, errorMessage);
                        // Give up, don't bother making any other changes to the package settings.
                        return false;
                    }
                } else {
                    requiredInstructionSet = instructionSet;
                    requirer = ps;
                }
            }
        }

        if (requiredInstructionSet != null) {
            String adjustedAbi;
            if (requirer != null) {
                // requirer != null implies that either scannedPackage was null or that scannedPackage
                // did not require an ABI, in which case we have to adjust scannedPackage to match
                // the ABI of the set (which is the same as requirer's ABI)
                adjustedAbi = requirer.cpuAbiString;
                if (scannedPackage != null) {
                    scannedPackage.applicationInfo.cpuAbi = adjustedAbi;
                }
            } else {
                // requirer == null implies that we're updating all ABIs in the set to
                // match scannedPackage.
                adjustedAbi =  scannedPackage.applicationInfo.cpuAbi;
            }

            for (PackageSetting ps : packagesForUser) {
                if (scannedPackage == null || !scannedPackage.packageName.equals(ps.name)) {
                    if (ps.cpuAbiString != null) {
                        continue;
                    }

                    ps.cpuAbiString = adjustedAbi;
                    if (ps.pkg != null && ps.pkg.applicationInfo != null) {
                        ps.pkg.applicationInfo.cpuAbi = adjustedAbi;
                        Slog.i(TAG, "Adjusting ABI for : " + ps.name + " to " + adjustedAbi);

                        if (performDexOptLI(ps.pkg, forceDexOpt, deferDexOpt, true) == DEX_OPT_FAILED) {
                            ps.cpuAbiString = null;
                            ps.pkg.applicationInfo.cpuAbi = null;
                            return false;
                        } else {
                            mInstaller.rmdex(ps.codePathString,
                                             getDexCodeInstructionSet(getPreferredInstructionSet()));
                        }
                    }
                }
            }
        }

        return true;
    }

    private void setUpCustomResolverActivity(PackageParser.Package pkg) {
        synchronized (mPackages) {
            mResolverReplaced = true;
            // Set up information for custom user intent resolution activity.
            mResolveActivity.applicationInfo = pkg.applicationInfo;
            mResolveActivity.name = mCustomResolverComponentName.getClassName();
            mResolveActivity.packageName = pkg.applicationInfo.packageName;
            mResolveActivity.processName = null;
            mResolveActivity.launchMode = ActivityInfo.LAUNCH_MULTIPLE;
            mResolveActivity.flags = ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS |
                    ActivityInfo.FLAG_FINISH_ON_CLOSE_SYSTEM_DIALOGS;
            mResolveActivity.theme = 0;
            mResolveActivity.exported = true;
            mResolveActivity.enabled = true;
            mResolveInfo.activityInfo = mResolveActivity;
            mResolveInfo.priority = 0;
            mResolveInfo.preferredOrder = 0;
            mResolveInfo.match = 0;
            mResolveComponentName = mCustomResolverComponentName;
            Slog.i(TAG, "Replacing default ResolverActivity with custom activity: " +
                    mResolveComponentName);
        }
    }

    private String calculateApkRoot(final String codePathString) {
        final File codePath = new File(codePathString);
        final File codeRoot;
        if (FileUtils.contains(Environment.getRootDirectory(), codePath)) {
            codeRoot = Environment.getRootDirectory();
        } else if (FileUtils.contains(Environment.getOemDirectory(), codePath)) {
            codeRoot = Environment.getOemDirectory();
        } else if (FileUtils.contains(Environment.getVendorDirectory(), codePath)) {
            codeRoot = Environment.getVendorDirectory();
        } else {
            // Unrecognized code path; take its top real segment as the apk root:
            // e.g. /something/app/blah.apk => /something
            try {
                File f = codePath.getCanonicalFile();
                File parent = f.getParentFile();    // non-null because codePath is a file
                File tmp;
                while ((tmp = parent.getParentFile()) != null) {
                    f = parent;
                    parent = tmp;
                }
                codeRoot = f;
                Slog.w(TAG, "Unrecognized code path "
                        + codePath + " - using " + codeRoot);
            } catch (IOException e) {
                // Can't canonicalize the lib path -- shenanigans?
                Slog.w(TAG, "Can't canonicalize code path " + codePath);
                return Environment.getRootDirectory().getPath();
            }
        }
        return codeRoot.getPath();
    }

    // This is the initial scan-time determination of how to handle a given
    // package for purposes of native library location.
    private void setInternalAppNativeLibraryPath(PackageParser.Package pkg,
            PackageSetting pkgSetting) {
        // "bundled" here means system-installed with no overriding update
        final boolean bundledApk = isSystemApp(pkg) && !isUpdatedSystemApp(pkg);
        final String apkName = getApkName(pkg.applicationInfo.sourceDir);
        final File libDir;
        if (bundledApk) {
            // If "/system/lib64/apkname" exists, assume that is the per-package
            // native library directory to use; otherwise use "/system/lib/apkname".
            String apkRoot = calculateApkRoot(pkg.applicationInfo.sourceDir);
            File lib64 = new File(apkRoot, LIB64_DIR_NAME);
            File packLib64 = new File(lib64, apkName);
            libDir = (packLib64.exists()) ? lib64 : new File(apkRoot, LIB_DIR_NAME);
        } else {
            libDir = mAppLibInstallDir;
        }
        final String nativeLibraryPath = (new File(libDir, apkName)).getPath();
        pkg.applicationInfo.nativeLibraryDir = nativeLibraryPath;
        pkgSetting.nativeLibraryPathString = nativeLibraryPath;
    }

    // Deduces the required ABI of an upgraded system app.
    private void setInternalAppAbi(PackageParser.Package pkg, PackageSetting pkgSetting) {
        final String apkRoot = calculateApkRoot(pkg.applicationInfo.sourceDir);
        final String apkName = getApkName(pkg.applicationInfo.sourceDir);

        // This is of the form "/system/lib64/<packagename>", "/vendor/lib64/<packagename>"
        // or similar.
        final File lib64 = new File(apkRoot, new File(LIB64_DIR_NAME, apkName).getPath());
        final File lib = new File(apkRoot, new File(LIB_DIR_NAME, apkName).getPath());

        // Assume that the bundled native libraries always correspond to the
        // most preferred 32 or 64 bit ABI.
        if (lib64.exists()) {
            pkg.applicationInfo.cpuAbi = Build.SUPPORTED_64_BIT_ABIS[0];
            pkgSetting.cpuAbiString = Build.SUPPORTED_64_BIT_ABIS[0];
        } else if (lib.exists()) {
            pkg.applicationInfo.cpuAbi = Build.SUPPORTED_32_BIT_ABIS[0];
            pkgSetting.cpuAbiString = Build.SUPPORTED_32_BIT_ABIS[0];
        } else {
            // This is the case where the app has no native code.
            pkg.applicationInfo.cpuAbi = null;
            pkgSetting.cpuAbiString = null;
        }
    }

    private static int copyNativeLibrariesForInternalApp(ApkHandle handle,
            final File nativeLibraryDir, String[] abiList) throws IOException {
        if (!nativeLibraryDir.isDirectory()) {
            nativeLibraryDir.delete();

            if (!nativeLibraryDir.mkdir()) {
                throw new IOException("Cannot create " + nativeLibraryDir.getPath());
            }

            try {
                Os.chmod(nativeLibraryDir.getPath(), S_IRWXU | S_IRGRP | S_IXGRP | S_IROTH | S_IXOTH);
            } catch (ErrnoException e) {
                throw new IOException("Cannot chmod native library directory "
                        + nativeLibraryDir.getPath(), e);
            }
        } else if (!SELinux.restorecon(nativeLibraryDir)) {
            throw new IOException("Cannot set SELinux context for " + nativeLibraryDir.getPath());
        }

        /*
         * If this is an internal application or our nativeLibraryPath points to
         * the app-lib directory, unpack the libraries if necessary.
         */
        int abi = NativeLibraryHelper.findSupportedAbi(handle, abiList);
        if (abi >= 0) {
            int copyRet = NativeLibraryHelper.copyNativeBinariesIfNeededLI(handle,
                    nativeLibraryDir, Build.SUPPORTED_ABIS[abi]);
            if (copyRet != PackageManager.INSTALL_SUCCEEDED) {
                return copyRet;
            }
        }

        return abi;
    }

    private void killApplication(String pkgName, int appId, String reason) {
        // Request the ActivityManager to kill the process(only for existing packages)
        // so that we do not end up in a confused state while the user is still using the older
        // version of the application while the new one gets installed.
        IActivityManager am = ActivityManagerNative.getDefault();
        if (am != null) {
            try {
                am.killApplicationWithAppId(pkgName, appId, reason);
            } catch (RemoteException e) {
            }
        }
    }

    void removePackageLI(PackageSetting ps, boolean chatty) {
        if (DEBUG_INSTALL) {
            if (chatty)
                Log.d(TAG, "Removing package " + ps.name);
        }

        // writer
        synchronized (mPackages) {
            mPackages.remove(ps.name);
            if (ps.codePathString != null) {
                mAppDirs.remove(ps.codePathString);
            }

            final PackageParser.Package pkg = ps.pkg;
            if (pkg != null) {
                cleanPackageDataStructuresLILPw(pkg, chatty);
            }
        }
    }

    void removeInstalledPackageLI(PackageParser.Package pkg, boolean chatty) {
        if (DEBUG_INSTALL) {
            if (chatty)
                Log.d(TAG, "Removing package " + pkg.applicationInfo.packageName);
        }

        // writer
        synchronized (mPackages) {
            mPackages.remove(pkg.applicationInfo.packageName);
            if (pkg.mPath != null) {
                mAppDirs.remove(pkg.mPath);
            }
            cleanPackageDataStructuresLILPw(pkg, chatty);
        }
    }

    void cleanPackageDataStructuresLILPw(PackageParser.Package pkg, boolean chatty) {
        int N = pkg.providers.size();
        StringBuilder r = null;
        int i;
        for (i=0; i<N; i++) {
            PackageParser.Provider p = pkg.providers.get(i);
            mProviders.removeProvider(p);
            if (p.info.authority == null) {

                /* There was another ContentProvider with this authority when
                 * this app was installed so this authority is null,
                 * Ignore it as we don't have to unregister the provider.
                 */
                continue;
            }
            String names[] = p.info.authority.split(";");
            for (int j = 0; j < names.length; j++) {
                if (mProvidersByAuthority.get(names[j]) == p) {
                    mProvidersByAuthority.remove(names[j]);
                    if (DEBUG_REMOVE) {
                        if (chatty)
                            Log.d(TAG, "Unregistered content provider: " + names[j]
                                    + ", className = " + p.info.name + ", isSyncable = "
                                    + p.info.isSyncable);
                    }
                }
            }
            if (DEBUG_REMOVE && chatty) {
                if (r == null) {
                    r = new StringBuilder(256);
                } else {
                    r.append(' ');
                }
                r.append(p.info.name);
            }
        }
        if (r != null) {
            if (DEBUG_REMOVE) Log.d(TAG, "  Providers: " + r);
        }

        N = pkg.services.size();
        r = null;
        for (i=0; i<N; i++) {
            PackageParser.Service s = pkg.services.get(i);
            mServices.removeService(s);
            if (chatty) {
                if (r == null) {
                    r = new StringBuilder(256);
                } else {
                    r.append(' ');
                }
                r.append(s.info.name);
            }
        }
        if (r != null) {
            if (DEBUG_REMOVE) Log.d(TAG, "  Services: " + r);
        }

        N = pkg.receivers.size();
        r = null;
        for (i=0; i<N; i++) {
            PackageParser.Activity a = pkg.receivers.get(i);
            mReceivers.removeActivity(a, "receiver");
            if (DEBUG_REMOVE && chatty) {
                if (r == null) {
                    r = new StringBuilder(256);
                } else {
                    r.append(' ');
                }
                r.append(a.info.name);
            }
        }
        if (r != null) {
            if (DEBUG_REMOVE) Log.d(TAG, "  Receivers: " + r);
        }

        N = pkg.activities.size();
        r = null;
        for (i=0; i<N; i++) {
            PackageParser.Activity a = pkg.activities.get(i);
            mActivities.removeActivity(a, "activity");
            if (DEBUG_REMOVE && chatty) {
                if (r == null) {
                    r = new StringBuilder(256);
                } else {
                    r.append(' ');
                }
                r.append(a.info.name);
            }
        }
        if (r != null) {
            if (DEBUG_REMOVE) Log.d(TAG, "  Activities: " + r);
        }

        N = pkg.permissions.size();
        r = null;
        for (i=0; i<N; i++) {
            PackageParser.Permission p = pkg.permissions.get(i);
            BasePermission bp = mSettings.mPermissions.get(p.info.name);
            if (bp == null) {
                bp = mSettings.mPermissionTrees.get(p.info.name);
            }
            if (bp != null && bp.perm == p) {
                bp.perm = null;
                if (DEBUG_REMOVE && chatty) {
                    if (r == null) {
                        r = new StringBuilder(256);
                    } else {
                        r.append(' ');
                    }
                    r.append(p.info.name);
                }
            }
        }
        if (r != null) {
            if (DEBUG_REMOVE) Log.d(TAG, "  Permissions: " + r);
        }

        N = pkg.instrumentation.size();
        r = null;
        for (i=0; i<N; i++) {
            PackageParser.Instrumentation a = pkg.instrumentation.get(i);
            mInstrumentation.remove(a.getComponentName());
            if (DEBUG_REMOVE && chatty) {
                if (r == null) {
                    r = new StringBuilder(256);
                } else {
                    r.append(' ');
                }
                r.append(a.info.name);
            }
        }
        if (r != null) {
            if (DEBUG_REMOVE) Log.d(TAG, "  Instrumentation: " + r);
        }

        r = null;
        if ((pkg.applicationInfo.flags&ApplicationInfo.FLAG_SYSTEM) != 0) {
            // Only system apps can hold shared libraries.
            if (pkg.libraryNames != null) {
                for (i=0; i<pkg.libraryNames.size(); i++) {
                    String name = pkg.libraryNames.get(i);
                    SharedLibraryEntry cur = mSharedLibraries.get(name);
                    if (cur != null && cur.apk != null && cur.apk.equals(pkg.packageName)) {
                        mSharedLibraries.remove(name);
                        if (DEBUG_REMOVE && chatty) {
                            if (r == null) {
                                r = new StringBuilder(256);
                            } else {
                                r.append(' ');
                            }
                            r.append(name);
                        }
                    }
                }
            }
        }
        if (r != null) {
            if (DEBUG_REMOVE) Log.d(TAG, "  Libraries: " + r);
        }
    }

    private static final boolean isPackageFilename(String name) {
        return name != null && name.endsWith(".apk");
    }

    private static boolean hasPermission(PackageParser.Package pkgInfo, String perm) {
        for (int i=pkgInfo.permissions.size()-1; i>=0; i--) {
            if (pkgInfo.permissions.get(i).info.name.equals(perm)) {
                return true;
            }
        }
        return false;
    }

    static final int UPDATE_PERMISSIONS_ALL = 1<<0;
    static final int UPDATE_PERMISSIONS_REPLACE_PKG = 1<<1;
    static final int UPDATE_PERMISSIONS_REPLACE_ALL = 1<<2;

    private void updatePermissionsLPw(String changingPkg,
            PackageParser.Package pkgInfo, int flags) {
        // Make sure there are no dangling permission trees.
        Iterator<BasePermission> it = mSettings.mPermissionTrees.values().iterator();
        while (it.hasNext()) {
            final BasePermission bp = it.next();
            if (bp.packageSetting == null) {
                // We may not yet have parsed the package, so just see if
                // we still know about its settings.
                bp.packageSetting = mSettings.mPackages.get(bp.sourcePackage);
            }
            if (bp.packageSetting == null) {
                Slog.w(TAG, "Removing dangling permission tree: " + bp.name
                        + " from package " + bp.sourcePackage);
                it.remove();
            } else if (changingPkg != null && changingPkg.equals(bp.sourcePackage)) {
                if (pkgInfo == null || !hasPermission(pkgInfo, bp.name)) {
                    Slog.i(TAG, "Removing old permission tree: " + bp.name
                            + " from package " + bp.sourcePackage);
                    flags |= UPDATE_PERMISSIONS_ALL;
                    it.remove();
                }
            }
        }

        // Make sure all dynamic permissions have been assigned to a package,
        // and make sure there are no dangling permissions.
        it = mSettings.mPermissions.values().iterator();
        while (it.hasNext()) {
            final BasePermission bp = it.next();
            if (bp.type == BasePermission.TYPE_DYNAMIC) {
                if (DEBUG_SETTINGS) Log.v(TAG, "Dynamic permission: name="
                        + bp.name + " pkg=" + bp.sourcePackage
                        + " info=" + bp.pendingInfo);
                if (bp.packageSetting == null && bp.pendingInfo != null) {
                    final BasePermission tree = findPermissionTreeLP(bp.name);
                    if (tree != null && tree.perm != null) {
                        bp.packageSetting = tree.packageSetting;
                        bp.perm = new PackageParser.Permission(tree.perm.owner,
                                new PermissionInfo(bp.pendingInfo));
                        bp.perm.info.packageName = tree.perm.info.packageName;
                        bp.perm.info.name = bp.name;
                        bp.uid = tree.uid;
                    }
                }
            }
            if (bp.packageSetting == null) {
                // We may not yet have parsed the package, so just see if
                // we still know about its settings.
                bp.packageSetting = mSettings.mPackages.get(bp.sourcePackage);
            }
            if (bp.packageSetting == null) {
                Slog.w(TAG, "Removing dangling permission: " + bp.name
                        + " from package " + bp.sourcePackage);
                it.remove();
            } else if (changingPkg != null && changingPkg.equals(bp.sourcePackage)) {
                if (pkgInfo == null || !hasPermission(pkgInfo, bp.name)) {
                    Slog.i(TAG, "Removing old permission: " + bp.name
                            + " from package " + bp.sourcePackage);
                    flags |= UPDATE_PERMISSIONS_ALL;
                    it.remove();
                }
            }
        }

        // Now update the permissions for all packages, in particular
        // replace the granted permissions of the system packages.
        if ((flags&UPDATE_PERMISSIONS_ALL) != 0) {
            for (PackageParser.Package pkg : mPackages.values()) {
                if (pkg != pkgInfo) {
                    grantPermissionsLPw(pkg, (flags&UPDATE_PERMISSIONS_REPLACE_ALL) != 0);
                }
            }
        }
        
        if (pkgInfo != null) {
            grantPermissionsLPw(pkgInfo, (flags&UPDATE_PERMISSIONS_REPLACE_PKG) != 0);
        }
    }

    private void grantPermissionsLPw(PackageParser.Package pkg, boolean replace) {
        final PackageSetting ps = (PackageSetting) pkg.mExtras;
        if (ps == null) {
            return;
        }
        final GrantedPermissions gp = ps.sharedUser != null ? ps.sharedUser : ps;
        HashSet<String> origPermissions = gp.grantedPermissions;
        boolean changedPermission = false;

        if (replace) {
            ps.permissionsFixed = false;
            if (gp == ps) {
                origPermissions = new HashSet<String>(gp.grantedPermissions);
                gp.grantedPermissions.clear();
                gp.gids = mGlobalGids;
            }
        }

        if (gp.gids == null) {
            gp.gids = mGlobalGids;
        }

        final int N = pkg.requestedPermissions.size();
        for (int i=0; i<N; i++) {
            final String name = pkg.requestedPermissions.get(i);
            final boolean required = pkg.requestedPermissionsRequired.get(i);
            final BasePermission bp = mSettings.mPermissions.get(name);
            if (DEBUG_INSTALL) {
                if (gp != ps) {
                    Log.i(TAG, "Package " + pkg.packageName + " checking " + name + ": " + bp);
                }
            }

            if (bp == null || bp.packageSetting == null) {
                Slog.w(TAG, "Unknown permission " + name
                        + " in package " + pkg.packageName);
                continue;
            }

            final String perm = bp.name;
            boolean allowed;
            boolean allowedSig = false;
            final int level = bp.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE;
            if (level == PermissionInfo.PROTECTION_NORMAL
                    || level == PermissionInfo.PROTECTION_DANGEROUS) {
                // We grant a normal or dangerous permission if any of the following
                // are true:
                // 1) The permission is required
                // 2) The permission is optional, but was granted in the past
                // 3) The permission is optional, but was requested by an
                //    app in /system (not /data)
                //
                // Otherwise, reject the permission.
                allowed = (required || origPermissions.contains(perm)
                        || (isSystemApp(ps) && !isUpdatedSystemApp(ps)));
            } else if (bp.packageSetting == null) {
                // This permission is invalid; skip it.
                allowed = false;
            } else if (level == PermissionInfo.PROTECTION_SIGNATURE) {
                allowed = grantSignaturePermission(perm, pkg, bp, origPermissions);
                if (allowed) {
                    allowedSig = true;
                }
            } else {
                allowed = false;
            }
            if (DEBUG_INSTALL) {
                if (gp != ps) {
                    Log.i(TAG, "Package " + pkg.packageName + " granting " + perm);
                }
            }
            if (allowed) {
                if (!isSystemApp(ps) && ps.permissionsFixed) {
                    // If this is an existing, non-system package, then
                    // we can't add any new permissions to it.
                    if (!allowedSig && !gp.grantedPermissions.contains(perm)) {
                        // Except...  if this is a permission that was added
                        // to the platform (note: need to only do this when
                        // updating the platform).
                        allowed = isNewPlatformPermissionForPackage(perm, pkg);
                    }
                }
                if (allowed) {
                    if (!gp.grantedPermissions.contains(perm)) {
                        changedPermission = true;
                        gp.grantedPermissions.add(perm);
                        gp.gids = appendInts(gp.gids, bp.gids);
                    } else if (!ps.haveGids) {
                        gp.gids = appendInts(gp.gids, bp.gids);
                    }
                } else {
                    Slog.w(TAG, "Not granting permission " + perm
                            + " to package " + pkg.packageName
                            + " because it was previously installed without");
                }
            } else {
                if (gp.grantedPermissions.remove(perm)) {
                    changedPermission = true;
                    gp.gids = removeInts(gp.gids, bp.gids);
                    Slog.i(TAG, "Un-granting permission " + perm
                            + " from package " + pkg.packageName
                            + " (protectionLevel=" + bp.protectionLevel
                            + " flags=0x" + Integer.toHexString(pkg.applicationInfo.flags)
                            + ")");
                } else {
                    Slog.w(TAG, "Not granting permission " + perm
                            + " to package " + pkg.packageName
                            + " (protectionLevel=" + bp.protectionLevel
                            + " flags=0x" + Integer.toHexString(pkg.applicationInfo.flags)
                            + ")");
                }
            }
        }

        if ((changedPermission || replace) && !ps.permissionsFixed &&
                !isSystemApp(ps) || isUpdatedSystemApp(ps)){
            // This is the first that we have heard about this package, so the
            // permissions we have now selected are fixed until explicitly
            // changed.
            ps.permissionsFixed = true;
        }
        ps.haveGids = true;
    }

    private boolean isNewPlatformPermissionForPackage(String perm, PackageParser.Package pkg) {
        boolean allowed = false;
        final int NP = PackageParser.NEW_PERMISSIONS.length;
        for (int ip=0; ip<NP; ip++) {
            final PackageParser.NewPermissionInfo npi
                    = PackageParser.NEW_PERMISSIONS[ip];
            if (npi.name.equals(perm)
                    && pkg.applicationInfo.targetSdkVersion < npi.sdkVersion) {
                allowed = true;
                Log.i(TAG, "Auto-granting " + perm + " to old pkg "
                        + pkg.packageName);
                break;
            }
        }
        return allowed;
    }

    private boolean grantSignaturePermission(String perm, PackageParser.Package pkg,
                                          BasePermission bp, HashSet<String> origPermissions) {
        boolean allowed;
        allowed = (compareSignatures(
                bp.packageSetting.signatures.mSignatures, pkg.mSignatures)
                        == PackageManager.SIGNATURE_MATCH)
                || (compareSignatures(mPlatformPackage.mSignatures, pkg.mSignatures)
                        == PackageManager.SIGNATURE_MATCH);
        if (!allowed && (bp.protectionLevel
                & PermissionInfo.PROTECTION_FLAG_SYSTEM) != 0) {
            if (isSystemApp(pkg)) {
                // For updated system applications, a system permission
                // is granted only if it had been defined by the original application.
                if (isUpdatedSystemApp(pkg)) {
                    final PackageSetting sysPs = mSettings
                            .getDisabledSystemPkgLPr(pkg.packageName);
                    final GrantedPermissions origGp = sysPs.sharedUser != null
                            ? sysPs.sharedUser : sysPs;

                    if (origGp.grantedPermissions.contains(perm)) {
                        // If the original was granted this permission, we take
                        // that grant decision as read and propagate it to the
                        // update.
                        allowed = true;
                    } else {
                        // The system apk may have been updated with an older
                        // version of the one on the data partition, but which
                        // granted a new system permission that it didn't have
                        // before.  In this case we do want to allow the app to
                        // now get the new permission if the ancestral apk is
                        // privileged to get it.
                        if (sysPs.pkg != null && sysPs.isPrivileged()) {
                            for (int j=0;
                                    j<sysPs.pkg.requestedPermissions.size(); j++) {
                                if (perm.equals(
                                        sysPs.pkg.requestedPermissions.get(j))) {
                                    allowed = true;
                                    break;
                                }
                            }
                        }
                    }
                } else {
                    allowed = isPrivilegedApp(pkg);
                }
            }
        }
        if (!allowed && (bp.protectionLevel
                & PermissionInfo.PROTECTION_FLAG_DEVELOPMENT) != 0) {
            // For development permissions, a development permission
            // is granted only if it was already granted.
            allowed = origPermissions.contains(perm);
        }
        return allowed;
    }

    final class ActivityIntentResolver
            extends IntentResolver<PackageParser.ActivityIntentInfo, ResolveInfo> {
        public List<ResolveInfo> queryIntent(Intent intent, String resolvedType,
                boolean defaultOnly, int userId) {
            if (!sUserManager.exists(userId)) return null;
            mFlags = defaultOnly ? PackageManager.MATCH_DEFAULT_ONLY : 0;
            return super.queryIntent(intent, resolvedType, defaultOnly, userId);
        }

        public List<ResolveInfo> queryIntent(Intent intent, String resolvedType, int flags,
                int userId) {
            if (!sUserManager.exists(userId)) return null;
            mFlags = flags;
            return super.queryIntent(intent, resolvedType,
                    (flags & PackageManager.MATCH_DEFAULT_ONLY) != 0, userId);
        }

        public List<ResolveInfo> queryIntentForPackage(Intent intent, String resolvedType,
                int flags, ArrayList<PackageParser.Activity> packageActivities, int userId) {
            if (!sUserManager.exists(userId)) return null;
            if (packageActivities == null) {
                return null;
            }
            mFlags = flags;
            final boolean defaultOnly = (flags&PackageManager.MATCH_DEFAULT_ONLY) != 0;
            final int N = packageActivities.size();
            ArrayList<PackageParser.ActivityIntentInfo[]> listCut =
                new ArrayList<PackageParser.ActivityIntentInfo[]>(N);

            ArrayList<PackageParser.ActivityIntentInfo> intentFilters;
            for (int i = 0; i < N; ++i) {
                intentFilters = packageActivities.get(i).intents;
                if (intentFilters != null && intentFilters.size() > 0) {
                    PackageParser.ActivityIntentInfo[] array =
                            new PackageParser.ActivityIntentInfo[intentFilters.size()];
                    intentFilters.toArray(array);
                    listCut.add(array);
                }
            }
            return super.queryIntentFromList(intent, resolvedType, defaultOnly, listCut, userId);
        }

        public final void addActivity(PackageParser.Activity a, String type) {
            final boolean systemApp = isSystemApp(a.info.applicationInfo);
            mActivities.put(a.getComponentName(), a);
            if (DEBUG_SHOW_INFO)
                Log.v(
                TAG, "  " + type + " " +
                (a.info.nonLocalizedLabel != null ? a.info.nonLocalizedLabel : a.info.name) + ":");
            if (DEBUG_SHOW_INFO)
                Log.v(TAG, "    Class=" + a.info.name);
            final int NI = a.intents.size();
            for (int j=0; j<NI; j++) {
                PackageParser.ActivityIntentInfo intent = a.intents.get(j);
                if (!systemApp && intent.getPriority() > 0 && "activity".equals(type)) {
                    intent.setPriority(0);
                    Log.w(TAG, "Package " + a.info.applicationInfo.packageName + " has activity "
                            + a.className + " with priority > 0, forcing to 0");
                }
                if (DEBUG_SHOW_INFO) {
                    Log.v(TAG, "    IntentFilter:");
                    intent.dump(new LogPrinter(Log.VERBOSE, TAG), "      ");
                }
                if (!intent.debugCheck()) {
                    Log.w(TAG, "==> For Activity " + a.info.name);
                }
                addFilter(intent);
            }
        }

        public final void removeActivity(PackageParser.Activity a, String type) {
            mActivities.remove(a.getComponentName());
            if (DEBUG_SHOW_INFO) {
                Log.v(TAG, "  " + type + " "
                        + (a.info.nonLocalizedLabel != null ? a.info.nonLocalizedLabel
                                : a.info.name) + ":");
                Log.v(TAG, "    Class=" + a.info.name);
            }
            final int NI = a.intents.size();
            for (int j=0; j<NI; j++) {
                PackageParser.ActivityIntentInfo intent = a.intents.get(j);
                if (DEBUG_SHOW_INFO) {
                    Log.v(TAG, "    IntentFilter:");
                    intent.dump(new LogPrinter(Log.VERBOSE, TAG), "      ");
                }
                removeFilter(intent);
            }
        }

        @Override
        protected boolean allowFilterResult(
                PackageParser.ActivityIntentInfo filter, List<ResolveInfo> dest) {
            ActivityInfo filterAi = filter.activity.info;
            for (int i=dest.size()-1; i>=0; i--) {
                ActivityInfo destAi = dest.get(i).activityInfo;
                if (destAi.name == filterAi.name
                        && destAi.packageName == filterAi.packageName) {
                    return false;
                }
            }
            return true;
        }

        @Override
        protected ActivityIntentInfo[] newArray(int size) {
            return new ActivityIntentInfo[size];
        }

        @Override
        protected boolean isFilterStopped(PackageParser.ActivityIntentInfo filter, int userId) {
            if (!sUserManager.exists(userId)) return true;
            PackageParser.Package p = filter.activity.owner;
            if (p != null) {
                PackageSetting ps = (PackageSetting)p.mExtras;
                if (ps != null) {
                    // System apps are never considered stopped for purposes of
                    // filtering, because there may be no way for the user to
                    // actually re-launch them.
                    return (ps.pkgFlags&ApplicationInfo.FLAG_SYSTEM) == 0
                            && ps.getStopped(userId);
                }
            }
            return false;
        }

        @Override
        protected boolean isPackageForFilter(String packageName,
                PackageParser.ActivityIntentInfo info) {
            return packageName.equals(info.activity.owner.packageName);
        }
        
        @Override
        protected ResolveInfo newResult(PackageParser.ActivityIntentInfo info,
                int match, int userId) {
            if (!sUserManager.exists(userId)) return null;
            if (!mSettings.isEnabledLPr(info.activity.info, mFlags, userId)) {
                return null;
            }
            final PackageParser.Activity activity = info.activity;
            if (mSafeMode && (activity.info.applicationInfo.flags
                    &ApplicationInfo.FLAG_SYSTEM) == 0) {
                return null;
            }
            PackageSetting ps = (PackageSetting) activity.owner.mExtras;
            if (ps == null) {
                return null;
            }
            ActivityInfo ai = PackageParser.generateActivityInfo(activity, mFlags,
                    ps.readUserState(userId), userId);
            if (ai == null) {
                return null;
            }
            final ResolveInfo res = new ResolveInfo();
            res.activityInfo = ai;
            if ((mFlags&PackageManager.GET_RESOLVED_FILTER) != 0) {
                res.filter = info;
            }
            res.priority = info.getPriority();
            res.preferredOrder = activity.owner.mPreferredOrder;
            //System.out.println("Result: " + res.activityInfo.className +
            //                   " = " + res.priority);
            res.match = match;
            res.isDefault = info.hasDefault;
            res.labelRes = info.labelRes;
            res.nonLocalizedLabel = info.nonLocalizedLabel;
            res.icon = info.icon;
            res.system = isSystemApp(res.activityInfo.applicationInfo);
            return res;
        }

        @Override
        protected void sortResults(List<ResolveInfo> results) {
            Collections.sort(results, mResolvePrioritySorter);
        }

        @Override
        protected void dumpFilter(PrintWriter out, String prefix,
                PackageParser.ActivityIntentInfo filter) {
            out.print(prefix); out.print(
                    Integer.toHexString(System.identityHashCode(filter.activity)));
                    out.print(' ');
                    filter.activity.printComponentShortName(out);
                    out.print(" filter ");
                    out.println(Integer.toHexString(System.identityHashCode(filter)));
        }

//        List<ResolveInfo> filterEnabled(List<ResolveInfo> resolveInfoList) {
//            final Iterator<ResolveInfo> i = resolveInfoList.iterator();
//            final List<ResolveInfo> retList = Lists.newArrayList();
//            while (i.hasNext()) {
//                final ResolveInfo resolveInfo = i.next();
//                if (isEnabledLP(resolveInfo.activityInfo)) {
//                    retList.add(resolveInfo);
//                }
//            }
//            return retList;
//        }

        // Keys are String (activity class name), values are Activity.
        private final HashMap<ComponentName, PackageParser.Activity> mActivities
                = new HashMap<ComponentName, PackageParser.Activity>();
        private int mFlags;
    }

    private final class ServiceIntentResolver
            extends IntentResolver<PackageParser.ServiceIntentInfo, ResolveInfo> {
        public List<ResolveInfo> queryIntent(Intent intent, String resolvedType,
                boolean defaultOnly, int userId) {
            mFlags = defaultOnly ? PackageManager.MATCH_DEFAULT_ONLY : 0;
            return super.queryIntent(intent, resolvedType, defaultOnly, userId);
        }

        public List<ResolveInfo> queryIntent(Intent intent, String resolvedType, int flags,
                int userId) {
            if (!sUserManager.exists(userId)) return null;
            mFlags = flags;
            return super.queryIntent(intent, resolvedType,
                    (flags & PackageManager.MATCH_DEFAULT_ONLY) != 0, userId);
        }

        public List<ResolveInfo> queryIntentForPackage(Intent intent, String resolvedType,
                int flags, ArrayList<PackageParser.Service> packageServices, int userId) {
            if (!sUserManager.exists(userId)) return null;
            if (packageServices == null) {
                return null;
            }
            mFlags = flags;
            final boolean defaultOnly = (flags&PackageManager.MATCH_DEFAULT_ONLY) != 0;
            final int N = packageServices.size();
            ArrayList<PackageParser.ServiceIntentInfo[]> listCut =
                new ArrayList<PackageParser.ServiceIntentInfo[]>(N);

            ArrayList<PackageParser.ServiceIntentInfo> intentFilters;
            for (int i = 0; i < N; ++i) {
                intentFilters = packageServices.get(i).intents;
                if (intentFilters != null && intentFilters.size() > 0) {
                    PackageParser.ServiceIntentInfo[] array =
                            new PackageParser.ServiceIntentInfo[intentFilters.size()];
                    intentFilters.toArray(array);
                    listCut.add(array);
                }
            }
            return super.queryIntentFromList(intent, resolvedType, defaultOnly, listCut, userId);
        }

        public final void addService(PackageParser.Service s) {
            mServices.put(s.getComponentName(), s);
            if (DEBUG_SHOW_INFO) {
                Log.v(TAG, "  "
                        + (s.info.nonLocalizedLabel != null
                        ? s.info.nonLocalizedLabel : s.info.name) + ":");
                Log.v(TAG, "    Class=" + s.info.name);
            }
            final int NI = s.intents.size();
            int j;
            for (j=0; j<NI; j++) {
                PackageParser.ServiceIntentInfo intent = s.intents.get(j);
                if (DEBUG_SHOW_INFO) {
                    Log.v(TAG, "    IntentFilter:");
                    intent.dump(new LogPrinter(Log.VERBOSE, TAG), "      ");
                }
                if (!intent.debugCheck()) {
                    Log.w(TAG, "==> For Service " + s.info.name);
                }
                addFilter(intent);
            }
        }

        public final void removeService(PackageParser.Service s) {
            mServices.remove(s.getComponentName());
            if (DEBUG_SHOW_INFO) {
                Log.v(TAG, "  " + (s.info.nonLocalizedLabel != null
                        ? s.info.nonLocalizedLabel : s.info.name) + ":");
                Log.v(TAG, "    Class=" + s.info.name);
            }
            final int NI = s.intents.size();
            int j;
            for (j=0; j<NI; j++) {
                PackageParser.ServiceIntentInfo intent = s.intents.get(j);
                if (DEBUG_SHOW_INFO) {
                    Log.v(TAG, "    IntentFilter:");
                    intent.dump(new LogPrinter(Log.VERBOSE, TAG), "      ");
                }
                removeFilter(intent);
            }
        }

        @Override
        protected boolean allowFilterResult(
                PackageParser.ServiceIntentInfo filter, List<ResolveInfo> dest) {
            ServiceInfo filterSi = filter.service.info;
            for (int i=dest.size()-1; i>=0; i--) {
                ServiceInfo destAi = dest.get(i).serviceInfo;
                if (destAi.name == filterSi.name
                        && destAi.packageName == filterSi.packageName) {
                    return false;
                }
            }
            return true;
        }

        @Override
        protected PackageParser.ServiceIntentInfo[] newArray(int size) {
            return new PackageParser.ServiceIntentInfo[size];
        }

        @Override
        protected boolean isFilterStopped(PackageParser.ServiceIntentInfo filter, int userId) {
            if (!sUserManager.exists(userId)) return true;
            PackageParser.Package p = filter.service.owner;
            if (p != null) {
                PackageSetting ps = (PackageSetting)p.mExtras;
                if (ps != null) {
                    // System apps are never considered stopped for purposes of
                    // filtering, because there may be no way for the user to
                    // actually re-launch them.
                    return (ps.pkgFlags & ApplicationInfo.FLAG_SYSTEM) == 0
                            && ps.getStopped(userId);
                }
            }
            return false;
        }

        @Override
        protected boolean isPackageForFilter(String packageName,
                PackageParser.ServiceIntentInfo info) {
            return packageName.equals(info.service.owner.packageName);
        }
        
        @Override
        protected ResolveInfo newResult(PackageParser.ServiceIntentInfo filter,
                int match, int userId) {
            if (!sUserManager.exists(userId)) return null;
            final PackageParser.ServiceIntentInfo info = (PackageParser.ServiceIntentInfo)filter;
            if (!mSettings.isEnabledLPr(info.service.info, mFlags, userId)) {
                return null;
            }
            final PackageParser.Service service = info.service;
            if (mSafeMode && (service.info.applicationInfo.flags
                    &ApplicationInfo.FLAG_SYSTEM) == 0) {
                return null;
            }
            PackageSetting ps = (PackageSetting) service.owner.mExtras;
            if (ps == null) {
                return null;
            }
            ServiceInfo si = PackageParser.generateServiceInfo(service, mFlags,
                    ps.readUserState(userId), userId);
            if (si == null) {
                return null;
            }
            final ResolveInfo res = new ResolveInfo();
            res.serviceInfo = si;
            if ((mFlags&PackageManager.GET_RESOLVED_FILTER) != 0) {
                res.filter = filter;
            }
            res.priority = info.getPriority();
            res.preferredOrder = service.owner.mPreferredOrder;
            //System.out.println("Result: " + res.activityInfo.className +
            //                   " = " + res.priority);
            res.match = match;
            res.isDefault = info.hasDefault;
            res.labelRes = info.labelRes;
            res.nonLocalizedLabel = info.nonLocalizedLabel;
            res.icon = info.icon;
            res.system = isSystemApp(res.serviceInfo.applicationInfo);
            return res;
        }

        @Override
        protected void sortResults(List<ResolveInfo> results) {
            Collections.sort(results, mResolvePrioritySorter);
        }

        @Override
        protected void dumpFilter(PrintWriter out, String prefix,
                PackageParser.ServiceIntentInfo filter) {
            out.print(prefix); out.print(
                    Integer.toHexString(System.identityHashCode(filter.service)));
                    out.print(' ');
                    filter.service.printComponentShortName(out);
                    out.print(" filter ");
                    out.println(Integer.toHexString(System.identityHashCode(filter)));
        }

//        List<ResolveInfo> filterEnabled(List<ResolveInfo> resolveInfoList) {
//            final Iterator<ResolveInfo> i = resolveInfoList.iterator();
//            final List<ResolveInfo> retList = Lists.newArrayList();
//            while (i.hasNext()) {
//                final ResolveInfo resolveInfo = (ResolveInfo) i;
//                if (isEnabledLP(resolveInfo.serviceInfo)) {
//                    retList.add(resolveInfo);
//                }
//            }
//            return retList;
//        }

        // Keys are String (activity class name), values are Activity.
        private final HashMap<ComponentName, PackageParser.Service> mServices
                = new HashMap<ComponentName, PackageParser.Service>();
        private int mFlags;
    };

    private final class ProviderIntentResolver
            extends IntentResolver<PackageParser.ProviderIntentInfo, ResolveInfo> {
        public List<ResolveInfo> queryIntent(Intent intent, String resolvedType,
                boolean defaultOnly, int userId) {
            mFlags = defaultOnly ? PackageManager.MATCH_DEFAULT_ONLY : 0;
            return super.queryIntent(intent, resolvedType, defaultOnly, userId);
        }

        public List<ResolveInfo> queryIntent(Intent intent, String resolvedType, int flags,
                int userId) {
            if (!sUserManager.exists(userId))
                return null;
            mFlags = flags;
            return super.queryIntent(intent, resolvedType,
                    (flags & PackageManager.MATCH_DEFAULT_ONLY) != 0, userId);
        }

        public List<ResolveInfo> queryIntentForPackage(Intent intent, String resolvedType,
                int flags, ArrayList<PackageParser.Provider> packageProviders, int userId) {
            if (!sUserManager.exists(userId))
                return null;
            if (packageProviders == null) {
                return null;
            }
            mFlags = flags;
            final boolean defaultOnly = (flags & PackageManager.MATCH_DEFAULT_ONLY) != 0;
            final int N = packageProviders.size();
            ArrayList<PackageParser.ProviderIntentInfo[]> listCut =
                    new ArrayList<PackageParser.ProviderIntentInfo[]>(N);

            ArrayList<PackageParser.ProviderIntentInfo> intentFilters;
            for (int i = 0; i < N; ++i) {
                intentFilters = packageProviders.get(i).intents;
                if (intentFilters != null && intentFilters.size() > 0) {
                    PackageParser.ProviderIntentInfo[] array =
                            new PackageParser.ProviderIntentInfo[intentFilters.size()];
                    intentFilters.toArray(array);
                    listCut.add(array);
                }
            }
            return super.queryIntentFromList(intent, resolvedType, defaultOnly, listCut, userId);
        }

        public final void addProvider(PackageParser.Provider p) {
            mProviders.put(p.getComponentName(), p);
            if (DEBUG_SHOW_INFO) {
                Log.v(TAG, "  "
                        + (p.info.nonLocalizedLabel != null
                                ? p.info.nonLocalizedLabel : p.info.name) + ":");
                Log.v(TAG, "    Class=" + p.info.name);
            }
            final int NI = p.intents.size();
            int j;
            for (j = 0; j < NI; j++) {
                PackageParser.ProviderIntentInfo intent = p.intents.get(j);
                if (DEBUG_SHOW_INFO) {
                    Log.v(TAG, "    IntentFilter:");
                    intent.dump(new LogPrinter(Log.VERBOSE, TAG), "      ");
                }
                if (!intent.debugCheck()) {
                    Log.w(TAG, "==> For Provider " + p.info.name);
                }
                addFilter(intent);
            }
        }

        public final void removeProvider(PackageParser.Provider p) {
            mProviders.remove(p.getComponentName());
            if (DEBUG_SHOW_INFO) {
                Log.v(TAG, "  " + (p.info.nonLocalizedLabel != null
                        ? p.info.nonLocalizedLabel : p.info.name) + ":");
                Log.v(TAG, "    Class=" + p.info.name);
            }
            final int NI = p.intents.size();
            int j;
            for (j = 0; j < NI; j++) {
                PackageParser.ProviderIntentInfo intent = p.intents.get(j);
                if (DEBUG_SHOW_INFO) {
                    Log.v(TAG, "    IntentFilter:");
                    intent.dump(new LogPrinter(Log.VERBOSE, TAG), "      ");
                }
                removeFilter(intent);
            }
        }

        @Override
        protected boolean allowFilterResult(
                PackageParser.ProviderIntentInfo filter, List<ResolveInfo> dest) {
            ProviderInfo filterPi = filter.provider.info;
            for (int i = dest.size() - 1; i >= 0; i--) {
                ProviderInfo destPi = dest.get(i).providerInfo;
                if (destPi.name == filterPi.name
                        && destPi.packageName == filterPi.packageName) {
                    return false;
                }
            }
            return true;
        }

        @Override
        protected PackageParser.ProviderIntentInfo[] newArray(int size) {
            return new PackageParser.ProviderIntentInfo[size];
        }

        @Override
        protected boolean isFilterStopped(PackageParser.ProviderIntentInfo filter, int userId) {
            if (!sUserManager.exists(userId))
                return true;
            PackageParser.Package p = filter.provider.owner;
            if (p != null) {
                PackageSetting ps = (PackageSetting) p.mExtras;
                if (ps != null) {
                    // System apps are never considered stopped for purposes of
                    // filtering, because there may be no way for the user to
                    // actually re-launch them.
                    return (ps.pkgFlags & ApplicationInfo.FLAG_SYSTEM) == 0
                            && ps.getStopped(userId);
                }
            }
            return false;
        }

        @Override
        protected boolean isPackageForFilter(String packageName,
                PackageParser.ProviderIntentInfo info) {
            return packageName.equals(info.provider.owner.packageName);
        }

        @Override
        protected ResolveInfo newResult(PackageParser.ProviderIntentInfo filter,
                int match, int userId) {
            if (!sUserManager.exists(userId))
                return null;
            final PackageParser.ProviderIntentInfo info = filter;
            if (!mSettings.isEnabledLPr(info.provider.info, mFlags, userId)) {
                return null;
            }
            final PackageParser.Provider provider = info.provider;
            if (mSafeMode && (provider.info.applicationInfo.flags
                    & ApplicationInfo.FLAG_SYSTEM) == 0) {
                return null;
            }
            PackageSetting ps = (PackageSetting) provider.owner.mExtras;
            if (ps == null) {
                return null;
            }
            ProviderInfo pi = PackageParser.generateProviderInfo(provider, mFlags,
                    ps.readUserState(userId), userId);
            if (pi == null) {
                return null;
            }
            final ResolveInfo res = new ResolveInfo();
            res.providerInfo = pi;
            if ((mFlags & PackageManager.GET_RESOLVED_FILTER) != 0) {
                res.filter = filter;
            }
            res.priority = info.getPriority();
            res.preferredOrder = provider.owner.mPreferredOrder;
            res.match = match;
            res.isDefault = info.hasDefault;
            res.labelRes = info.labelRes;
            res.nonLocalizedLabel = info.nonLocalizedLabel;
            res.icon = info.icon;
            res.system = isSystemApp(res.providerInfo.applicationInfo);
            return res;
        }

        @Override
        protected void sortResults(List<ResolveInfo> results) {
            Collections.sort(results, mResolvePrioritySorter);
        }

        @Override
        protected void dumpFilter(PrintWriter out, String prefix,
                PackageParser.ProviderIntentInfo filter) {
            out.print(prefix);
            out.print(
                    Integer.toHexString(System.identityHashCode(filter.provider)));
            out.print(' ');
            filter.provider.printComponentShortName(out);
            out.print(" filter ");
            out.println(Integer.toHexString(System.identityHashCode(filter)));
        }

        private final HashMap<ComponentName, PackageParser.Provider> mProviders
                = new HashMap<ComponentName, PackageParser.Provider>();
        private int mFlags;
    };

    private static final Comparator<ResolveInfo> mResolvePrioritySorter =
            new Comparator<ResolveInfo>() {
        public int compare(ResolveInfo r1, ResolveInfo r2) {
            int v1 = r1.priority;
            int v2 = r2.priority;
            //System.out.println("Comparing: q1=" + q1 + " q2=" + q2);
            if (v1 != v2) {
                return (v1 > v2) ? -1 : 1;
            }
            v1 = r1.preferredOrder;
            v2 = r2.preferredOrder;
            if (v1 != v2) {
                return (v1 > v2) ? -1 : 1;
            }
            if (r1.isDefault != r2.isDefault) {
                return r1.isDefault ? -1 : 1;
            }
            v1 = r1.match;
            v2 = r2.match;
            //System.out.println("Comparing: m1=" + m1 + " m2=" + m2);
            if (v1 != v2) {
                return (v1 > v2) ? -1 : 1;
            }
            if (r1.system != r2.system) {
                return r1.system ? -1 : 1;
            }
            return 0;
        }
    };

    private static final Comparator<ProviderInfo> mProviderInitOrderSorter =
            new Comparator<ProviderInfo>() {
        public int compare(ProviderInfo p1, ProviderInfo p2) {
            final int v1 = p1.initOrder;
            final int v2 = p2.initOrder;
            return (v1 > v2) ? -1 : ((v1 < v2) ? 1 : 0);
        }
    };

    static final void sendPackageBroadcast(String action, String pkg,
            Bundle extras, String targetPkg, IIntentReceiver finishedReceiver,
            int[] userIds) {
        IActivityManager am = ActivityManagerNative.getDefault();
        if (am != null) {
            try {
                if (userIds == null) {
                    userIds = am.getRunningUserIds();
                }
                for (int id : userIds) {
                    final Intent intent = new Intent(action,
                            pkg != null ? Uri.fromParts("package", pkg, null) : null);
                    if (extras != null) {
                        intent.putExtras(extras);
                    }
                    if (targetPkg != null) {
                        intent.setPackage(targetPkg);
                    }
                    // Modify the UID when posting to other users
                    int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
                    if (uid > 0 && UserHandle.getUserId(uid) != id) {
                        uid = UserHandle.getUid(id, UserHandle.getAppId(uid));
                        intent.putExtra(Intent.EXTRA_UID, uid);
                    }
                    intent.putExtra(Intent.EXTRA_USER_HANDLE, id);
                    intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                    if (DEBUG_BROADCASTS) {
                        RuntimeException here = new RuntimeException("here");
                        here.fillInStackTrace();
                        Slog.d(TAG, "Sending to user " + id + ": "
                                + intent.toShortString(false, true, false, false)
                                + " " + intent.getExtras(), here);
                    }
                    am.broadcastIntent(null, intent, null, finishedReceiver,
                            0, null, null, null, android.app.AppOpsManager.OP_NONE,
                            finishedReceiver != null, false, id);
                }
            } catch (RemoteException ex) {
            }
        }
    }

    /**
     * Check if the external storage media is available. This is true if there
     * is a mounted external storage medium or if the external storage is
     * emulated.
     */
    private boolean isExternalMediaAvailable() {
        return mMediaMounted || Environment.isExternalStorageEmulated();
    }

    @Override
    public PackageCleanItem nextPackageToClean(PackageCleanItem lastPackage) {
        // writer
        synchronized (mPackages) {
            if (!isExternalMediaAvailable()) {
                // If the external storage is no longer mounted at this point,
                // the caller may not have been able to delete all of this
                // packages files and can not delete any more.  Bail.
                return null;
            }
            final ArrayList<PackageCleanItem> pkgs = mSettings.mPackagesToBeCleaned;
            if (lastPackage != null) {
                pkgs.remove(lastPackage);
            }
            if (pkgs.size() > 0) {
                return pkgs.get(0);
            }
        }
        return null;
    }

    void schedulePackageCleaning(String packageName, int userId, boolean andCode) {
        if (false) {
            RuntimeException here = new RuntimeException("here");
            here.fillInStackTrace();
            Slog.d(TAG, "Schedule cleaning " + packageName + " user=" + userId
                    + " andCode=" + andCode, here);
        }
        mHandler.sendMessage(mHandler.obtainMessage(START_CLEANING_PACKAGE,
                userId, andCode ? 1 : 0, packageName));
    }
    
    void startCleaningPackages() {
        // reader
        synchronized (mPackages) {
            if (!isExternalMediaAvailable()) {
                return;
            }
            if (mSettings.mPackagesToBeCleaned.isEmpty()) {
                return;
            }
        }
        Intent intent = new Intent(PackageManager.ACTION_CLEAN_EXTERNAL_STORAGE);
        intent.setComponent(DEFAULT_CONTAINER_COMPONENT);
        IActivityManager am = ActivityManagerNative.getDefault();
        if (am != null) {
            try {
                am.startService(null, intent, null, UserHandle.USER_OWNER);
            } catch (RemoteException e) {
            }
        }
    }
    
    private final class AppDirObserver extends FileObserver {
        public AppDirObserver(String path, int mask, boolean isrom, boolean isPrivileged) {
            super(path, mask);
            mRootDir = path;
            mIsRom = isrom;
            mIsPrivileged = isPrivileged;
        }

        public void onEvent(int event, String path) {
            String removedPackage = null;
            int removedAppId = -1;
            int[] removedUsers = null;
            String addedPackage = null;
            int addedAppId = -1;
            int[] addedUsers = null;

            // TODO post a message to the handler to obtain serial ordering
            synchronized (mInstallLock) {
                String fullPathStr = null;
                File fullPath = null;
                if (path != null) {
                    fullPath = new File(mRootDir, path);
                    fullPathStr = fullPath.getPath();
                }

                if (DEBUG_APP_DIR_OBSERVER)
                    Log.v(TAG, "File " + fullPathStr + " changed: " + Integer.toHexString(event));

                if (!isPackageFilename(path)) {
                    if (DEBUG_APP_DIR_OBSERVER)
                        Log.v(TAG, "Ignoring change of non-package file: " + fullPathStr);
                    return;
                }

                // Ignore packages that are being installed or
                // have just been installed.
                if (ignoreCodePath(fullPathStr)) {
                    return;
                }
                PackageParser.Package p = null;
                PackageSetting ps = null;
                // reader
                synchronized (mPackages) {
                    p = mAppDirs.get(fullPathStr);
                    if (p != null) {
                        ps = mSettings.mPackages.get(p.applicationInfo.packageName);
                        if (ps != null) {
                            removedUsers = ps.queryInstalledUsers(sUserManager.getUserIds(), true);
                        } else {
                            removedUsers = sUserManager.getUserIds();
                        }
                    }
                    addedUsers = sUserManager.getUserIds();
                }
                if ((event&REMOVE_EVENTS) != 0) {
                    if (ps != null) {
                        if (DEBUG_REMOVE) Slog.d(TAG, "Package disappeared: " + ps);
                        removePackageLI(ps, true);
                        removedPackage = ps.name;
                        removedAppId = ps.appId;
                    }
                }

                if ((event&ADD_EVENTS) != 0) {
                    if (p == null) {
                        if (DEBUG_INSTALL) Slog.d(TAG, "New file appeared: " + fullPath);
                        int flags = PackageParser.PARSE_CHATTY | PackageParser.PARSE_MUST_BE_APK;
                        if (mIsRom) {
                            flags |= PackageParser.PARSE_IS_SYSTEM
                                    | PackageParser.PARSE_IS_SYSTEM_DIR;
                            if (mIsPrivileged) {
                                flags |= PackageParser.PARSE_IS_PRIVILEGED;
                            }
                        }
                        p = scanPackageLI(fullPath, flags,
                                SCAN_MONITOR | SCAN_NO_PATHS | SCAN_UPDATE_TIME,
                                System.currentTimeMillis(), UserHandle.ALL, null);
                        if (p != null) {
                            /*
                             * TODO this seems dangerous as the package may have
                             * changed since we last acquired the mPackages
                             * lock.
                             */
                            // writer
                            synchronized (mPackages) {
                                updatePermissionsLPw(p.packageName, p,
                                        p.permissions.size() > 0 ? UPDATE_PERMISSIONS_ALL : 0);
                            }
                            addedPackage = p.applicationInfo.packageName;
                            addedAppId = UserHandle.getAppId(p.applicationInfo.uid);
                        }
                    }
                }

                // reader
                synchronized (mPackages) {
                    mSettings.writeLPr();
                }
            }

            if (removedPackage != null) {
                Bundle extras = new Bundle(1);
                extras.putInt(Intent.EXTRA_UID, removedAppId);
                extras.putBoolean(Intent.EXTRA_DATA_REMOVED, false);
                sendPackageBroadcast(Intent.ACTION_PACKAGE_REMOVED, removedPackage,
                        extras, null, null, removedUsers);
            }
            if (addedPackage != null) {
                Bundle extras = new Bundle(1);
                extras.putInt(Intent.EXTRA_UID, addedAppId);
                sendPackageBroadcast(Intent.ACTION_PACKAGE_ADDED, addedPackage,
                        extras, null, null, addedUsers);
            }
        }

        private final String mRootDir;
        private final boolean mIsRom;
        private final boolean mIsPrivileged;
    }

    /* Called when a downloaded package installation has been confirmed by the user */
    public void installPackage(
            final Uri packageURI, final IPackageInstallObserver observer, final int flags) {
        installPackage(packageURI, observer, flags, null);
    }

    /* Called when a downloaded package installation has been confirmed by the user */
    @Override
    public void installPackage(
            final Uri packageURI, final IPackageInstallObserver observer, final int flags,
            final String installerPackageName) {
        installPackageWithVerification(packageURI, observer, flags, installerPackageName, null,
                null, null);
    }

    @Override
    public void installPackageWithVerification(Uri packageURI, IPackageInstallObserver observer,
            int flags, String installerPackageName, Uri verificationURI,
            ManifestDigest manifestDigest, ContainerEncryptionParams encryptionParams) {
        VerificationParams verificationParams = new VerificationParams(verificationURI, null, null,
                VerificationParams.NO_UID, manifestDigest);
        installPackageWithVerificationAndEncryption(packageURI, observer, flags,
                installerPackageName, verificationParams, encryptionParams);
    }

    @Override
    public void installPackageWithVerificationAndEncryption(Uri packageURI,
            IPackageInstallObserver observer, int flags, String installerPackageName,
            VerificationParams verificationParams, ContainerEncryptionParams encryptionParams) {
        installPackageWithVerificationEncryptionAndAbiOverride(packageURI, observer, flags,
                installerPackageName, verificationParams, encryptionParams, null);
    }

    @Override
    public void installPackageWithVerificationEncryptionAndAbiOverride(Uri packageURI,
            IPackageInstallObserver observer, int flags, String installerPackageName,
            VerificationParams verificationParams, ContainerEncryptionParams encryptionParams,
            String packageAbiOverride) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.INSTALL_PACKAGES,
                null);

        final int uid = Binder.getCallingUid();
        if (isUserRestricted(UserHandle.getUserId(uid), UserManager.DISALLOW_INSTALL_APPS)) {
            try {
                observer.packageInstalled("", PackageManager.INSTALL_FAILED_USER_RESTRICTED);
            } catch (RemoteException re) {
            }
            return;
        }

        UserHandle user;
        if ((flags&PackageManager.INSTALL_ALL_USERS) != 0) {
            user = UserHandle.ALL;
        } else {
            user = new UserHandle(UserHandle.getUserId(uid));
        }

        final int filteredFlags;

        if (uid == Process.SHELL_UID || uid == 0) {
            if (DEBUG_INSTALL) {
                Slog.v(TAG, "Install from ADB");
            }
            filteredFlags = flags | PackageManager.INSTALL_FROM_ADB;
        } else {
            filteredFlags = flags & ~PackageManager.INSTALL_FROM_ADB;
        }

        verificationParams.setInstallerUid(uid);

        final Message msg = mHandler.obtainMessage(INIT_COPY);
        msg.obj = new InstallParams(packageURI, observer, filteredFlags, installerPackageName,
                verificationParams, encryptionParams, user,
                packageAbiOverride);
        mHandler.sendMessage(msg);
    }

    private void sendPackageAddedForUser(String packageName, PackageSetting pkgSetting, int userId) {
        Bundle extras = new Bundle(1);
        extras.putInt(Intent.EXTRA_UID, UserHandle.getUid(userId, pkgSetting.appId));

        sendPackageBroadcast(Intent.ACTION_PACKAGE_ADDED,
                packageName, extras, null, null, new int[] {userId});
        try {
            IActivityManager am = ActivityManagerNative.getDefault();
            final boolean isSystem =
                    isSystemApp(pkgSetting) || isUpdatedSystemApp(pkgSetting);
            if (isSystem && am.isUserRunning(userId, false)) {
                // The just-installed/enabled app is bundled on the system, so presumed
                // to be able to run automatically without needing an explicit launch.
                // Send it a BOOT_COMPLETED if it would ordinarily have gotten one.
                Intent bcIntent = new Intent(Intent.ACTION_BOOT_COMPLETED)
                        .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                        .setPackage(packageName);
                am.broadcastIntent(null, bcIntent, null, null, 0, null, null, null,
                        android.app.AppOpsManager.OP_NONE, false, false, userId);
            }
        } catch (RemoteException e) {
            // shouldn't happen
            Slog.w(TAG, "Unable to bootstrap installed package", e);
        }
    }

    @Override
    public boolean setApplicationBlockedSettingAsUser(String packageName, boolean blocked,
            int userId) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USERS, null);
        PackageSetting pkgSetting;
        final int uid = Binder.getCallingUid();
        if (UserHandle.getUserId(uid) != userId) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.INTERACT_ACROSS_USERS_FULL,
                    "setApplicationBlockedSetting for user " + userId);
        }

        if (blocked && isPackageDeviceAdmin(packageName, userId)) {
            Slog.w(TAG, "Not blocking package " + packageName + ": has active device admin");
            return false;
        }

        long callingId = Binder.clearCallingIdentity();
        try {
            boolean sendAdded = false;
            boolean sendRemoved = false;
            // writer
            synchronized (mPackages) {
                pkgSetting = mSettings.mPackages.get(packageName);
                if (pkgSetting == null) {
                    return false;
                }
                if (pkgSetting.getBlocked(userId) != blocked) {
                    pkgSetting.setBlocked(blocked, userId);
                    mSettings.writePackageRestrictionsLPr(userId);
                    if (blocked) {
                        sendRemoved = true;
                    } else {
                        sendAdded = true;
                    }
                }
            }
            if (sendAdded) {
                sendPackageAddedForUser(packageName, pkgSetting, userId);
                return true;
            }
            if (sendRemoved) {
                killApplication(packageName, UserHandle.getUid(userId, pkgSetting.appId),
                        "blocking pkg");
                sendPackageBlockedForUser(packageName, pkgSetting, userId);
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
        return false;
    }

    private void sendPackageBlockedForUser(String packageName, PackageSetting pkgSetting,
            int userId) {
        final PackageRemovedInfo info = new PackageRemovedInfo();
        info.removedPackage = packageName;
        info.removedUsers = new int[] {userId};
        info.uid = UserHandle.getUid(userId, pkgSetting.appId);
        info.sendBroadcast(false, false, false);
    }

    /**
     * Returns true if application is not found or there was an error. Otherwise it returns
     * the blocked state of the package for the given user.
     */
    @Override
    public boolean getApplicationBlockedSettingAsUser(String packageName, int userId) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USERS, null);
        PackageSetting pkgSetting;
        final int uid = Binder.getCallingUid();
        if (UserHandle.getUserId(uid) != userId) {
            mContext.enforceCallingPermission(
                    android.Manifest.permission.INTERACT_ACROSS_USERS_FULL,
                    "getApplicationBlocked for user " + userId);
        }
        long callingId = Binder.clearCallingIdentity();
        try {
            // writer
            synchronized (mPackages) {
                pkgSetting = mSettings.mPackages.get(packageName);
                if (pkgSetting == null) {
                    return true;
                }
                return pkgSetting.getBlocked(userId);
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    /**
     * @hide
     */
    @Override
    public int installExistingPackageAsUser(String packageName, int userId) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.INSTALL_PACKAGES,
                null);
        PackageSetting pkgSetting;
        final int uid = Binder.getCallingUid();
        if (UserHandle.getUserId(uid) != userId) {
            mContext.enforceCallingPermission(
                    android.Manifest.permission.INTERACT_ACROSS_USERS_FULL,
                    "installExistingPackage for user " + userId);
        }
        if (isUserRestricted(userId, UserManager.DISALLOW_INSTALL_APPS)) {
            return PackageManager.INSTALL_FAILED_USER_RESTRICTED;
        }

        long callingId = Binder.clearCallingIdentity();
        try {
            boolean sendAdded = false;
            Bundle extras = new Bundle(1);

            // writer
            synchronized (mPackages) {
                pkgSetting = mSettings.mPackages.get(packageName);
                if (pkgSetting == null) {
                    return PackageManager.INSTALL_FAILED_INVALID_URI;
                }
                if (!pkgSetting.getInstalled(userId)) {
                    pkgSetting.setInstalled(true, userId);
                    pkgSetting.setBlocked(false, userId);
                    mSettings.writePackageRestrictionsLPr(userId);
                    sendAdded = true;
                }
            }

            if (sendAdded) {
                sendPackageAddedForUser(packageName, pkgSetting, userId);
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }

        return PackageManager.INSTALL_SUCCEEDED;
    }

    private boolean isUserRestricted(int userId, String restrictionKey) {
        Bundle restrictions = sUserManager.getUserRestrictions(userId);
        if (restrictions.getBoolean(restrictionKey, false)) {
            Log.w(TAG, "User is restricted: " + restrictionKey);
            return true;
        }
        return false;
    }

    @Override
    public void verifyPendingInstall(int id, int verificationCode) throws RemoteException {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.PACKAGE_VERIFICATION_AGENT,
                "Only package verification agents can verify applications");

        final Message msg = mHandler.obtainMessage(PACKAGE_VERIFIED);
        final PackageVerificationResponse response = new PackageVerificationResponse(
                verificationCode, Binder.getCallingUid());
        msg.arg1 = id;
        msg.obj = response;
        mHandler.sendMessage(msg);
    }

    @Override
    public void extendVerificationTimeout(int id, int verificationCodeAtTimeout,
            long millisecondsToDelay) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.PACKAGE_VERIFICATION_AGENT,
                "Only package verification agents can extend verification timeouts");

        final PackageVerificationState state = mPendingVerification.get(id);
        final PackageVerificationResponse response = new PackageVerificationResponse(
                verificationCodeAtTimeout, Binder.getCallingUid());

        if (millisecondsToDelay > PackageManager.MAXIMUM_VERIFICATION_TIMEOUT) {
            millisecondsToDelay = PackageManager.MAXIMUM_VERIFICATION_TIMEOUT;
        }
        if (millisecondsToDelay < 0) {
            millisecondsToDelay = 0;
        }
        if ((verificationCodeAtTimeout != PackageManager.VERIFICATION_ALLOW)
                && (verificationCodeAtTimeout != PackageManager.VERIFICATION_REJECT)) {
            verificationCodeAtTimeout = PackageManager.VERIFICATION_REJECT;
        }

        if ((state != null) && !state.timeoutExtended()) {
            state.extendTimeout();

            final Message msg = mHandler.obtainMessage(PACKAGE_VERIFIED);
            msg.arg1 = id;
            msg.obj = response;
            mHandler.sendMessageDelayed(msg, millisecondsToDelay);
        }
    }

    private void broadcastPackageVerified(int verificationId, Uri packageUri,
            int verificationCode, UserHandle user) {
        final Intent intent = new Intent(Intent.ACTION_PACKAGE_VERIFIED);
        intent.setDataAndType(packageUri, PACKAGE_MIME_TYPE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.putExtra(PackageManager.EXTRA_VERIFICATION_ID, verificationId);
        intent.putExtra(PackageManager.EXTRA_VERIFICATION_RESULT, verificationCode);

        mContext.sendBroadcastAsUser(intent, user,
                android.Manifest.permission.PACKAGE_VERIFICATION_AGENT);
    }

    private ComponentName matchComponentForVerifier(String packageName,
            List<ResolveInfo> receivers) {
        ActivityInfo targetReceiver = null;

        final int NR = receivers.size();
        for (int i = 0; i < NR; i++) {
            final ResolveInfo info = receivers.get(i);
            if (info.activityInfo == null) {
                continue;
            }

            if (packageName.equals(info.activityInfo.packageName)) {
                targetReceiver = info.activityInfo;
                break;
            }
        }

        if (targetReceiver == null) {
            return null;
        }

        return new ComponentName(targetReceiver.packageName, targetReceiver.name);
    }

    private List<ComponentName> matchVerifiers(PackageInfoLite pkgInfo,
            List<ResolveInfo> receivers, final PackageVerificationState verificationState) {
        if (pkgInfo.verifiers.length == 0) {
            return null;
        }

        final int N = pkgInfo.verifiers.length;
        final List<ComponentName> sufficientVerifiers = new ArrayList<ComponentName>(N + 1);
        for (int i = 0; i < N; i++) {
            final VerifierInfo verifierInfo = pkgInfo.verifiers[i];

            final ComponentName comp = matchComponentForVerifier(verifierInfo.packageName,
                    receivers);
            if (comp == null) {
                continue;
            }

            final int verifierUid = getUidForVerifier(verifierInfo);
            if (verifierUid == -1) {
                continue;
            }

            if (DEBUG_VERIFY) {
                Slog.d(TAG, "Added sufficient verifier " + verifierInfo.packageName
                        + " with the correct signature");
            }
            sufficientVerifiers.add(comp);
            verificationState.addSufficientVerifier(verifierUid);
        }

        return sufficientVerifiers;
    }

    private int getUidForVerifier(VerifierInfo verifierInfo) {
        synchronized (mPackages) {
            final PackageParser.Package pkg = mPackages.get(verifierInfo.packageName);
            if (pkg == null) {
                return -1;
            } else if (pkg.mSignatures.length != 1) {
                Slog.i(TAG, "Verifier package " + verifierInfo.packageName
                        + " has more than one signature; ignoring");
                return -1;
            }

            /*
             * If the public key of the package's signature does not match
             * our expected public key, then this is a different package and
             * we should skip.
             */

            final byte[] expectedPublicKey;
            try {
                final Signature verifierSig = pkg.mSignatures[0];
                final PublicKey publicKey = verifierSig.getPublicKey();
                expectedPublicKey = publicKey.getEncoded();
            } catch (CertificateException e) {
                return -1;
            }

            final byte[] actualPublicKey = verifierInfo.publicKey.getEncoded();

            if (!Arrays.equals(actualPublicKey, expectedPublicKey)) {
                Slog.i(TAG, "Verifier package " + verifierInfo.packageName
                        + " does not have the expected public key; ignoring");
                return -1;
            }

            return pkg.applicationInfo.uid;
        }
    }

    @Override
    public void finishPackageInstall(int token) {
        enforceSystemOrRoot("Only the system is allowed to finish installs");

        if (DEBUG_INSTALL) {
            Slog.v(TAG, "BM finishing package install for " + token);
        }

        final Message msg = mHandler.obtainMessage(POST_INSTALL, token, 0);
        mHandler.sendMessage(msg);
    }

    /**
     * Get the verification agent timeout.
     *
     * @return verification timeout in milliseconds
     */
    private long getVerificationTimeout() {
        return android.provider.Settings.Global.getLong(mContext.getContentResolver(),
                android.provider.Settings.Global.PACKAGE_VERIFIER_TIMEOUT,
                DEFAULT_VERIFICATION_TIMEOUT);
    }

    /**
     * Get the default verification agent response code.
     *
     * @return default verification response code
     */
    private int getDefaultVerificationResponse() {
        return android.provider.Settings.Global.getInt(mContext.getContentResolver(),
                android.provider.Settings.Global.PACKAGE_VERIFIER_DEFAULT_RESPONSE,
                DEFAULT_VERIFICATION_RESPONSE);
    }

    /**
     * Check whether or not package verification has been enabled.
     *
     * @return true if verification should be performed
     */
    private boolean isVerificationEnabled(int flags) {
        if (!DEFAULT_VERIFY_ENABLE) {
            return false;
        }

        // Check if installing from ADB
        if ((flags & PackageManager.INSTALL_FROM_ADB) != 0) {
            // Do not run verification in a test harness environment
            if (ActivityManager.isRunningInTestHarness()) {
                return false;
            }
            // Check if the developer does not want package verification for ADB installs
            if (android.provider.Settings.Global.getInt(mContext.getContentResolver(),
                    android.provider.Settings.Global.PACKAGE_VERIFIER_INCLUDE_ADB, 1) == 0) {
                return false;
            }
        }

        return android.provider.Settings.Global.getInt(mContext.getContentResolver(),
                android.provider.Settings.Global.PACKAGE_VERIFIER_ENABLE, 1) == 1;
    }

    /**
     * Get the "allow unknown sources" setting.
     *
     * @return the current "allow unknown sources" setting
     */
    private int getUnknownSourcesSettings() {
        return android.provider.Settings.Global.getInt(mContext.getContentResolver(),
                android.provider.Settings.Global.INSTALL_NON_MARKET_APPS,
                -1);
    }

    @Override
    public void setInstallerPackageName(String targetPackage, String installerPackageName) {
        final int uid = Binder.getCallingUid();
        // writer
        synchronized (mPackages) {
            PackageSetting targetPackageSetting = mSettings.mPackages.get(targetPackage);
            if (targetPackageSetting == null) {
                throw new IllegalArgumentException("Unknown target package: " + targetPackage);
            }

            PackageSetting installerPackageSetting;
            if (installerPackageName != null) {
                installerPackageSetting = mSettings.mPackages.get(installerPackageName);
                if (installerPackageSetting == null) {
                    throw new IllegalArgumentException("Unknown installer package: "
                            + installerPackageName);
                }
            } else {
                installerPackageSetting = null;
            }

            Signature[] callerSignature;
            Object obj = mSettings.getUserIdLPr(uid);
            if (obj != null) {
                if (obj instanceof SharedUserSetting) {
                    callerSignature = ((SharedUserSetting)obj).signatures.mSignatures;
                } else if (obj instanceof PackageSetting) {
                    callerSignature = ((PackageSetting)obj).signatures.mSignatures;
                } else {
                    throw new SecurityException("Bad object " + obj + " for uid " + uid);
                }
            } else {
                throw new SecurityException("Unknown calling uid " + uid);
            }

            // Verify: can't set installerPackageName to a package that is
            // not signed with the same cert as the caller.
            if (installerPackageSetting != null) {
                if (compareSignatures(callerSignature,
                        installerPackageSetting.signatures.mSignatures)
                        != PackageManager.SIGNATURE_MATCH) {
                    throw new SecurityException(
                            "Caller does not have same cert as new installer package "
                            + installerPackageName);
                }
            }

            // Verify: if target already has an installer package, it must
            // be signed with the same cert as the caller.
            if (targetPackageSetting.installerPackageName != null) {
                PackageSetting setting = mSettings.mPackages.get(
                        targetPackageSetting.installerPackageName);
                // If the currently set package isn't valid, then it's always
                // okay to change it.
                if (setting != null) {
                    if (compareSignatures(callerSignature,
                            setting.signatures.mSignatures)
                            != PackageManager.SIGNATURE_MATCH) {
                        throw new SecurityException(
                                "Caller does not have same cert as old installer package "
                                + targetPackageSetting.installerPackageName);
                    }
                }
            }

            // Okay!
            targetPackageSetting.installerPackageName = installerPackageName;
            scheduleWriteSettingsLocked();
        }
    }

    private void processPendingInstall(final InstallArgs args, final int currentStatus) {
        // Queue up an async operation since the package installation may take a little while.
        mHandler.post(new Runnable() {
            public void run() {
                mHandler.removeCallbacks(this);
                 // Result object to be returned
                PackageInstalledInfo res = new PackageInstalledInfo();
                res.returnCode = currentStatus;
                res.uid = -1;
                res.pkg = null;
                res.removedInfo = new PackageRemovedInfo();
                if (res.returnCode == PackageManager.INSTALL_SUCCEEDED) {
                    args.doPreInstall(res.returnCode);
                    synchronized (mInstallLock) {
                        installPackageLI(args, true, res);
                    }
                    args.doPostInstall(res.returnCode, res.uid);
                }

                // A restore should be performed at this point if (a) the install
                // succeeded, (b) the operation is not an update, and (c) the new
                // package has a backupAgent defined.
                final boolean update = res.removedInfo.removedPackage != null;
                boolean doRestore = (!update
                        && res.pkg != null
                        && res.pkg.applicationInfo.backupAgentName != null);

                // Set up the post-install work request bookkeeping.  This will be used
                // and cleaned up by the post-install event handling regardless of whether
                // there's a restore pass performed.  Token values are >= 1.
                int token;
                if (mNextInstallToken < 0) mNextInstallToken = 1;
                token = mNextInstallToken++;

                PostInstallData data = new PostInstallData(args, res);
                mRunningInstalls.put(token, data);
                if (DEBUG_INSTALL) Log.v(TAG, "+ starting restore round-trip " + token);

                if (res.returnCode == PackageManager.INSTALL_SUCCEEDED && doRestore) {
                    // Pass responsibility to the Backup Manager.  It will perform a
                    // restore if appropriate, then pass responsibility back to the
                    // Package Manager to run the post-install observer callbacks
                    // and broadcasts.
                    IBackupManager bm = IBackupManager.Stub.asInterface(
                            ServiceManager.getService(Context.BACKUP_SERVICE));
                    if (bm != null) {
                        if (DEBUG_INSTALL) Log.v(TAG, "token " + token
                                + " to BM for possible restore");
                        try {
                            bm.restoreAtInstall(res.pkg.applicationInfo.packageName, token);
                        } catch (RemoteException e) {
                            // can't happen; the backup manager is local
                        } catch (Exception e) {
                            Slog.e(TAG, "Exception trying to enqueue restore", e);
                            doRestore = false;
                        }
                    } else {
                        Slog.e(TAG, "Backup Manager not found!");
                        doRestore = false;
                    }
                }

                if (!doRestore) {
                    // No restore possible, or the Backup Manager was mysteriously not
                    // available -- just fire the post-install work request directly.
                    if (DEBUG_INSTALL) Log.v(TAG, "No restore - queue post-install for " + token);
                    Message msg = mHandler.obtainMessage(POST_INSTALL, token, 0);
                    mHandler.sendMessage(msg);
                }
            }
        });
    }

    private abstract class HandlerParams {
        private static final int MAX_RETRIES = 4;

        /**
         * Number of times startCopy() has been attempted and had a non-fatal
         * error.
         */
        private int mRetries = 0;

        /** User handle for the user requesting the information or installation. */
        private final UserHandle mUser;

        HandlerParams(UserHandle user) {
            mUser = user;
        }

        UserHandle getUser() {
            return mUser;
        }

        final boolean startCopy() {
            boolean res;
            try {
                if (DEBUG_INSTALL) Slog.i(TAG, "startCopy " + mUser + ": " + this);

                if (++mRetries > MAX_RETRIES) {
                    Slog.w(TAG, "Failed to invoke remote methods on default container service. Giving up");
                    mHandler.sendEmptyMessage(MCS_GIVE_UP);
                    handleServiceError();
                    return false;
                } else {
                    handleStartCopy();
                    res = true;
                }
            } catch (RemoteException e) {
                if (DEBUG_INSTALL) Slog.i(TAG, "Posting install MCS_RECONNECT");
                mHandler.sendEmptyMessage(MCS_RECONNECT);
                res = false;
            }
            handleReturnCode();
            return res;
        }

        final void serviceError() {
            if (DEBUG_INSTALL) Slog.i(TAG, "serviceError");
            handleServiceError();
            handleReturnCode();
        }

        abstract void handleStartCopy() throws RemoteException;
        abstract void handleServiceError();
        abstract void handleReturnCode();
    }

    class MeasureParams extends HandlerParams {
        private final PackageStats mStats;
        private boolean mSuccess;

        private final IPackageStatsObserver mObserver;

        public MeasureParams(PackageStats stats, IPackageStatsObserver observer) {
            super(new UserHandle(stats.userHandle));
            mObserver = observer;
            mStats = stats;
        }

        @Override
        public String toString() {
            return "MeasureParams{"
                + Integer.toHexString(System.identityHashCode(this))
                + " " + mStats.packageName + "}";
        }

        @Override
        void handleStartCopy() throws RemoteException {
            synchronized (mInstallLock) {
                mSuccess = getPackageSizeInfoLI(mStats.packageName, mStats.userHandle, mStats);
            }

            final boolean mounted;
            if (Environment.isExternalStorageEmulated()) {
                mounted = true;
            } else {
                final String status = Environment.getExternalStorageState();
                mounted = (Environment.MEDIA_MOUNTED.equals(status)
                        || Environment.MEDIA_MOUNTED_READ_ONLY.equals(status));
            }

            if (mounted) {
                final UserEnvironment userEnv = new UserEnvironment(mStats.userHandle);

                mStats.externalCacheSize = calculateDirectorySize(mContainerService,
                        userEnv.buildExternalStorageAppCacheDirs(mStats.packageName));

                mStats.externalDataSize = calculateDirectorySize(mContainerService,
                        userEnv.buildExternalStorageAppDataDirs(mStats.packageName));

                // Always subtract cache size, since it's a subdirectory
                mStats.externalDataSize -= mStats.externalCacheSize;

                mStats.externalMediaSize = calculateDirectorySize(mContainerService,
                        userEnv.buildExternalStorageAppMediaDirs(mStats.packageName));

                mStats.externalObbSize = calculateDirectorySize(mContainerService,
                        userEnv.buildExternalStorageAppObbDirs(mStats.packageName));
            }
        }

        @Override
        void handleReturnCode() {
            if (mObserver != null) {
                try {
                    mObserver.onGetStatsCompleted(mStats, mSuccess);
                } catch (RemoteException e) {
                    Slog.i(TAG, "Observer no longer exists.");
                }
            }
        }

        @Override
        void handleServiceError() {
            Slog.e(TAG, "Could not measure application " + mStats.packageName
                            + " external storage");
        }
    }

    private static long calculateDirectorySize(IMediaContainerService mcs, File[] paths)
            throws RemoteException {
        long result = 0;
        for (File path : paths) {
            result += mcs.calculateDirectorySize(path.getAbsolutePath());
        }
        return result;
    }

    private static void clearDirectory(IMediaContainerService mcs, File[] paths) {
        for (File path : paths) {
            try {
                mcs.clearDirectory(path.getAbsolutePath());
            } catch (RemoteException e) {
            }
        }
    }

    class InstallParams extends HandlerParams {
        final IPackageInstallObserver observer;
        int flags;

        private final Uri mPackageURI;
        final String installerPackageName;
        final VerificationParams verificationParams;
        private InstallArgs mArgs;
        private int mRet;
        private File mTempPackage;
        final ContainerEncryptionParams encryptionParams;
        final String packageAbiOverride;
        final String packageInstructionSetOverride;

        InstallParams(Uri packageURI,
                IPackageInstallObserver observer, int flags,
                String installerPackageName, VerificationParams verificationParams,
                ContainerEncryptionParams encryptionParams, UserHandle user,
                String packageAbiOverride) {
            super(user);
            this.mPackageURI = packageURI;
            this.flags = flags;
            this.observer = observer;
            this.installerPackageName = installerPackageName;
            this.verificationParams = verificationParams;
            this.encryptionParams = encryptionParams;
            this.packageAbiOverride = packageAbiOverride;
            this.packageInstructionSetOverride = (packageAbiOverride == null) ?
                    packageAbiOverride : VMRuntime.getInstructionSet(packageAbiOverride);
        }

        @Override
        public String toString() {
            return "InstallParams{"
                + Integer.toHexString(System.identityHashCode(this))
                + " " + mPackageURI + "}";
        }

        public ManifestDigest getManifestDigest() {
            if (verificationParams == null) {
                return null;
            }
            return verificationParams.getManifestDigest();
        }

        private int installLocationPolicy(PackageInfoLite pkgLite, int flags) {
            String packageName = pkgLite.packageName;
            int installLocation = pkgLite.installLocation;
            boolean onSd = (flags & PackageManager.INSTALL_EXTERNAL) != 0;
            // reader
            synchronized (mPackages) {
                PackageParser.Package pkg = mPackages.get(packageName);
                if (pkg != null) {
                    if ((flags & PackageManager.INSTALL_REPLACE_EXISTING) != 0) {
                        // Check for downgrading.
                        if ((flags & PackageManager.INSTALL_ALLOW_DOWNGRADE) == 0) {
                            if (pkgLite.versionCode < pkg.mVersionCode) {
                                Slog.w(TAG, "Can't install update of " + packageName
                                        + " update version " + pkgLite.versionCode
                                        + " is older than installed version "
                                        + pkg.mVersionCode);
                                return PackageHelper.RECOMMEND_FAILED_VERSION_DOWNGRADE;
                            }
                        }
                        // Check for updated system application.
                        if ((pkg.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                            if (onSd) {
                                Slog.w(TAG, "Cannot install update to system app on sdcard");
                                return PackageHelper.RECOMMEND_FAILED_INVALID_LOCATION;
                            }
                            return PackageHelper.RECOMMEND_INSTALL_INTERNAL;
                        } else {
                            if (onSd) {
                                // Install flag overrides everything.
                                return PackageHelper.RECOMMEND_INSTALL_EXTERNAL;
                            }
                            // If current upgrade specifies particular preference
                            if (installLocation == PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY) {
                                // Application explicitly specified internal.
                                return PackageHelper.RECOMMEND_INSTALL_INTERNAL;
                            } else if (installLocation == PackageInfo.INSTALL_LOCATION_PREFER_EXTERNAL) {
                                // App explictly prefers external. Let policy decide
                            } else {
                                // Prefer previous location
                                if (isExternal(pkg)) {
                                    return PackageHelper.RECOMMEND_INSTALL_EXTERNAL;
                                }
                                return PackageHelper.RECOMMEND_INSTALL_INTERNAL;
                            }
                        }
                    } else {
                        // Invalid install. Return error code
                        return PackageHelper.RECOMMEND_FAILED_ALREADY_EXISTS;
                    }
                }
            }
            // All the special cases have been taken care of.
            // Return result based on recommended install location.
            if (onSd) {
                return PackageHelper.RECOMMEND_INSTALL_EXTERNAL;
            }
            return pkgLite.recommendedInstallLocation;
        }

        /*
         * Invoke remote method to get package information and install
         * location values. Override install location based on default
         * policy if needed and then create install arguments based
         * on the install location.
         */
        public void handleStartCopy() throws RemoteException {
            int ret = PackageManager.INSTALL_SUCCEEDED;
            final boolean onSd = (flags & PackageManager.INSTALL_EXTERNAL) != 0;
            final boolean onInt = (flags & PackageManager.INSTALL_INTERNAL) != 0;
            PackageInfoLite pkgLite = null;

            if (onInt && onSd) {
                // Check if both bits are set.
                Slog.w(TAG, "Conflicting flags specified for installing on both internal and external");
                ret = PackageManager.INSTALL_FAILED_INVALID_INSTALL_LOCATION;
            } else {
                final long lowThreshold;

                final DeviceStorageMonitorService dsm = (DeviceStorageMonitorService) ServiceManager
                        .getService(DeviceStorageMonitorService.SERVICE);
                if (dsm == null) {
                    Log.w(TAG, "Couldn't get low memory threshold; no free limit imposed");
                    lowThreshold = 0L;
                } else {
                    lowThreshold = dsm.getMemoryLowThreshold();
                }

                try {
                    mContext.grantUriPermission(DEFAULT_CONTAINER_PACKAGE, mPackageURI,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);

                    final File packageFile;
                    if (encryptionParams != null || !"file".equals(mPackageURI.getScheme())) {
                        mTempPackage = createTempPackageFile(mDrmAppPrivateInstallDir);
                        if (mTempPackage != null) {
                            ParcelFileDescriptor out;
                            try {
                                out = ParcelFileDescriptor.open(mTempPackage,
                                        ParcelFileDescriptor.MODE_READ_WRITE);
                            } catch (FileNotFoundException e) {
                                out = null;
                                Slog.e(TAG, "Failed to create temporary file for : " + mPackageURI);
                            }

                            // Make a temporary file for decryption.
                            ret = mContainerService
                                    .copyResource(mPackageURI, encryptionParams, out);
                            IoUtils.closeQuietly(out);

                            packageFile = mTempPackage;

                            FileUtils.setPermissions(packageFile.getAbsolutePath(),
                                    FileUtils.S_IRUSR | FileUtils.S_IWUSR | FileUtils.S_IRGRP
                                            | FileUtils.S_IROTH,
                                    -1, -1);
                        } else {
                            packageFile = null;
                        }
                    } else {
                        packageFile = new File(mPackageURI.getPath());
                    }

                    if (packageFile != null) {
                        // Remote call to find out default install location
                        final String packageFilePath = packageFile.getAbsolutePath();
                        pkgLite = mContainerService.getMinimalPackageInfo(packageFilePath, flags,
                                lowThreshold, packageAbiOverride);

                        /*
                         * If we have too little free space, try to free cache
                         * before giving up.
                         */
                        if (pkgLite.recommendedInstallLocation
                                == PackageHelper.RECOMMEND_FAILED_INSUFFICIENT_STORAGE) {
                            final long size = mContainerService.calculateInstalledSize(
                                    packageFilePath, isForwardLocked(), packageAbiOverride);
                            if (mInstaller.freeCache(size + lowThreshold) >= 0) {
                                pkgLite = mContainerService.getMinimalPackageInfo(packageFilePath,
                                        flags, lowThreshold, packageAbiOverride);
                            }
                            /*
                             * The cache free must have deleted the file we
                             * downloaded to install.
                             *
                             * TODO: fix the "freeCache" call to not delete
                             *       the file we care about.
                             */
                            if (pkgLite.recommendedInstallLocation
                                    == PackageHelper.RECOMMEND_FAILED_INVALID_URI) {
                                pkgLite.recommendedInstallLocation
                                    = PackageHelper.RECOMMEND_FAILED_INSUFFICIENT_STORAGE;
                            }
                        }
                    }
                } finally {
                    mContext.revokeUriPermission(mPackageURI,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                }
            }

            if (ret == PackageManager.INSTALL_SUCCEEDED) {
                int loc = pkgLite.recommendedInstallLocation;
                if (loc == PackageHelper.RECOMMEND_FAILED_INVALID_LOCATION) {
                    ret = PackageManager.INSTALL_FAILED_INVALID_INSTALL_LOCATION;
                } else if (loc == PackageHelper.RECOMMEND_FAILED_ALREADY_EXISTS) {
                    ret = PackageManager.INSTALL_FAILED_ALREADY_EXISTS;
                } else if (loc == PackageHelper.RECOMMEND_FAILED_INSUFFICIENT_STORAGE) {
                    ret = PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE;
                } else if (loc == PackageHelper.RECOMMEND_FAILED_INVALID_APK) {
                    ret = PackageManager.INSTALL_FAILED_INVALID_APK;
                } else if (loc == PackageHelper.RECOMMEND_FAILED_INVALID_URI) {
                    ret = PackageManager.INSTALL_FAILED_INVALID_URI;
                } else if (loc == PackageHelper.RECOMMEND_MEDIA_UNAVAILABLE) {
                    ret = PackageManager.INSTALL_FAILED_MEDIA_UNAVAILABLE;
                } else {
                    // Override with defaults if needed.
                    loc = installLocationPolicy(pkgLite, flags);
                    if (loc == PackageHelper.RECOMMEND_FAILED_VERSION_DOWNGRADE) {
                        ret = PackageManager.INSTALL_FAILED_VERSION_DOWNGRADE;
                    } else if (!onSd && !onInt) {
                        // Override install location with flags
                        if (loc == PackageHelper.RECOMMEND_INSTALL_EXTERNAL) {
                            // Set the flag to install on external media.
                            flags |= PackageManager.INSTALL_EXTERNAL;
                            flags &= ~PackageManager.INSTALL_INTERNAL;
                        } else {
                            // Make sure the flag for installing on external
                            // media is unset
                            flags |= PackageManager.INSTALL_INTERNAL;
                            flags &= ~PackageManager.INSTALL_EXTERNAL;
                        }
                    }
                }
            }

            final InstallArgs args = createInstallArgs(this);
            mArgs = args;

            if (ret == PackageManager.INSTALL_SUCCEEDED) {
                 /*
                 * ADB installs appear as UserHandle.USER_ALL, and can only be performed by
                 * UserHandle.USER_OWNER, so use the package verifier for UserHandle.USER_OWNER.
                 */
                int userIdentifier = getUser().getIdentifier();
                if (userIdentifier == UserHandle.USER_ALL
                        && ((flags & PackageManager.INSTALL_FROM_ADB) != 0)) {
                    userIdentifier = UserHandle.USER_OWNER;
                }

                /*
                 * Determine if we have any installed package verifiers. If we
                 * do, then we'll defer to them to verify the packages.
                 */
                final int requiredUid = mRequiredVerifierPackage == null ? -1
                        : getPackageUid(mRequiredVerifierPackage, userIdentifier);
                if (requiredUid != -1 && isVerificationEnabled(flags)) {
                    final Intent verification = new Intent(
                            Intent.ACTION_PACKAGE_NEEDS_VERIFICATION);
                    verification.setDataAndType(getPackageUri(), PACKAGE_MIME_TYPE);
                    verification.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                    final List<ResolveInfo> receivers = queryIntentReceivers(verification,
                            PACKAGE_MIME_TYPE, PackageManager.GET_DISABLED_COMPONENTS,
                            0 /* TODO: Which userId? */);

                    if (DEBUG_VERIFY) {
                        Slog.d(TAG, "Found " + receivers.size() + " verifiers for intent "
                                + verification.toString() + " with " + pkgLite.verifiers.length
                                + " optional verifiers");
                    }

                    final int verificationId = mPendingVerificationToken++;

                    verification.putExtra(PackageManager.EXTRA_VERIFICATION_ID, verificationId);

                    verification.putExtra(PackageManager.EXTRA_VERIFICATION_INSTALLER_PACKAGE,
                            installerPackageName);

                    verification.putExtra(PackageManager.EXTRA_VERIFICATION_INSTALL_FLAGS, flags);

                    verification.putExtra(PackageManager.EXTRA_VERIFICATION_PACKAGE_NAME,
                            pkgLite.packageName);

                    verification.putExtra(PackageManager.EXTRA_VERIFICATION_VERSION_CODE,
                            pkgLite.versionCode);

                    if (verificationParams != null) {
                        if (verificationParams.getVerificationURI() != null) {
                           verification.putExtra(PackageManager.EXTRA_VERIFICATION_URI,
                                 verificationParams.getVerificationURI());
                        }
                        if (verificationParams.getOriginatingURI() != null) {
                            verification.putExtra(Intent.EXTRA_ORIGINATING_URI,
                                  verificationParams.getOriginatingURI());
                        }
                        if (verificationParams.getReferrer() != null) {
                            verification.putExtra(Intent.EXTRA_REFERRER,
                                  verificationParams.getReferrer());
                        }
                        if (verificationParams.getOriginatingUid() >= 0) {
                            verification.putExtra(Intent.EXTRA_ORIGINATING_UID,
                                  verificationParams.getOriginatingUid());
                        }
                        if (verificationParams.getInstallerUid() >= 0) {
                            verification.putExtra(PackageManager.EXTRA_VERIFICATION_INSTALLER_UID,
                                  verificationParams.getInstallerUid());
                        }
                    }

                    final PackageVerificationState verificationState = new PackageVerificationState(
                            requiredUid, args);

                    mPendingVerification.append(verificationId, verificationState);

                    final List<ComponentName> sufficientVerifiers = matchVerifiers(pkgLite,
                            receivers, verificationState);

                    /*
                     * If any sufficient verifiers were listed in the package
                     * manifest, attempt to ask them.
                     */
                    if (sufficientVerifiers != null) {
                        final int N = sufficientVerifiers.size();
                        if (N == 0) {
                            Slog.i(TAG, "Additional verifiers required, but none installed.");
                            ret = PackageManager.INSTALL_FAILED_VERIFICATION_FAILURE;
                        } else {
                            for (int i = 0; i < N; i++) {
                                final ComponentName verifierComponent = sufficientVerifiers.get(i);

                                final Intent sufficientIntent = new Intent(verification);
                                sufficientIntent.setComponent(verifierComponent);

                                mContext.sendBroadcastAsUser(sufficientIntent, getUser());
                            }
                        }
                    }

                    final ComponentName requiredVerifierComponent = matchComponentForVerifier(
                            mRequiredVerifierPackage, receivers);
                    if (ret == PackageManager.INSTALL_SUCCEEDED
                            && mRequiredVerifierPackage != null) {
                        /*
                         * Send the intent to the required verification agent,
                         * but only start the verification timeout after the
                         * target BroadcastReceivers have run.
                         */
                        verification.setComponent(requiredVerifierComponent);
                        mContext.sendOrderedBroadcastAsUser(verification, getUser(),
                                android.Manifest.permission.PACKAGE_VERIFICATION_AGENT,
                                new BroadcastReceiver() {
                                    @Override
                                    public void onReceive(Context context, Intent intent) {
                                        final Message msg = mHandler
                                                .obtainMessage(CHECK_PENDING_VERIFICATION);
                                        msg.arg1 = verificationId;
                                        mHandler.sendMessageDelayed(msg, getVerificationTimeout());
                                    }
                                }, null, 0, null, null);

                        /*
                         * We don't want the copy to proceed until verification
                         * succeeds, so null out this field.
                         */
                        mArgs = null;
                    }
                } else {
                    /*
                     * No package verification is enabled, so immediately start
                     * the remote call to initiate copy using temporary file.
                     */
                    ret = args.copyApk(mContainerService, true);
                }
            }

            mRet = ret;
        }

        @Override
        void handleReturnCode() {
            // If mArgs is null, then MCS couldn't be reached. When it
            // reconnects, it will try again to install. At that point, this
            // will succeed.
            if (mArgs != null) {
                processPendingInstall(mArgs, mRet);

                if (mTempPackage != null) {
                    if (!mTempPackage.delete()) {
                        Slog.w(TAG, "Couldn't delete temporary file: " +
                                mTempPackage.getAbsolutePath());
                    }
                }
            }
        }

        @Override
        void handleServiceError() {
            mArgs = createInstallArgs(this);
            mRet = PackageManager.INSTALL_FAILED_INTERNAL_ERROR;
        }

        public boolean isForwardLocked() {
            return (flags & PackageManager.INSTALL_FORWARD_LOCK) != 0;
        }

        public Uri getPackageUri() {
            if (mTempPackage != null) {
                return Uri.fromFile(mTempPackage);
            } else {
                return mPackageURI;
            }
        }
    }

    /*
     * Utility class used in movePackage api.
     * srcArgs and targetArgs are not set for invalid flags and make
     * sure to do null checks when invoking methods on them.
     * We probably want to return ErrorPrams for both failed installs
     * and moves.
     */
    class MoveParams extends HandlerParams {
        final IPackageMoveObserver observer;
        final int flags;
        final String packageName;
        final InstallArgs srcArgs;
        final InstallArgs targetArgs;
        int uid;
        int mRet;

        MoveParams(InstallArgs srcArgs, IPackageMoveObserver observer, int flags,
                String packageName, String dataDir, String instructionSet,
                int uid, UserHandle user) {
            super(user);
            this.srcArgs = srcArgs;
            this.observer = observer;
            this.flags = flags;
            this.packageName = packageName;
            this.uid = uid;
            if (srcArgs != null) {
                Uri packageUri = Uri.fromFile(new File(srcArgs.getCodePath()));
                targetArgs = createInstallArgs(packageUri, flags, packageName, dataDir, instructionSet);
            } else {
                targetArgs = null;
            }
        }

        @Override
        public String toString() {
            return "MoveParams{"
                + Integer.toHexString(System.identityHashCode(this))
                + " " + packageName + "}";
        }

        public void handleStartCopy() throws RemoteException {
            mRet = PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE;
            // Check for storage space on target medium
            if (!targetArgs.checkFreeStorage(mContainerService)) {
                Log.w(TAG, "Insufficient storage to install");
                return;
            }

            mRet = srcArgs.doPreCopy();
            if (mRet != PackageManager.INSTALL_SUCCEEDED) {
                return;
            }

            mRet = targetArgs.copyApk(mContainerService, false);
            if (mRet != PackageManager.INSTALL_SUCCEEDED) {
                srcArgs.doPostCopy(uid);
                return;
            }

            mRet = srcArgs.doPostCopy(uid);
            if (mRet != PackageManager.INSTALL_SUCCEEDED) {
                return;
            }

            mRet = targetArgs.doPreInstall(mRet);
            if (mRet != PackageManager.INSTALL_SUCCEEDED) {
                return;
            }

            if (DEBUG_SD_INSTALL) {
                StringBuilder builder = new StringBuilder();
                if (srcArgs != null) {
                    builder.append("src: ");
                    builder.append(srcArgs.getCodePath());
                }
                if (targetArgs != null) {
                    builder.append(" target : ");
                    builder.append(targetArgs.getCodePath());
                }
                Log.i(TAG, builder.toString());
            }
        }

        @Override
        void handleReturnCode() {
            targetArgs.doPostInstall(mRet, uid);
            int currentStatus = PackageManager.MOVE_FAILED_INTERNAL_ERROR;
            if (mRet == PackageManager.INSTALL_SUCCEEDED) {
                currentStatus = PackageManager.MOVE_SUCCEEDED;
            } else if (mRet == PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE){
                currentStatus = PackageManager.MOVE_FAILED_INSUFFICIENT_STORAGE;
            }
            processPendingMove(this, currentStatus);
        }

        @Override
        void handleServiceError() {
            mRet = PackageManager.INSTALL_FAILED_INTERNAL_ERROR;
        }
    }

    /**
     * Used during creation of InstallArgs
     *
     * @param flags package installation flags
     * @return true if should be installed on external storage
     */
    private static boolean installOnSd(int flags) {
        if ((flags & PackageManager.INSTALL_INTERNAL) != 0) {
            return false;
        }
        if ((flags & PackageManager.INSTALL_EXTERNAL) != 0) {
            return true;
        }
        return false;
    }

    /**
     * Used during creation of InstallArgs
     *
     * @param flags package installation flags
     * @return true if should be installed as forward locked
     */
    private static boolean installForwardLocked(int flags) {
        return (flags & PackageManager.INSTALL_FORWARD_LOCK) != 0;
    }

    private InstallArgs createInstallArgs(InstallParams params) {
        if (installOnSd(params.flags) || params.isForwardLocked()) {
            return new AsecInstallArgs(params);
        } else {
            return new FileInstallArgs(params);
        }
    }

    private InstallArgs createInstallArgs(int flags, String fullCodePath, String fullResourcePath,
            String nativeLibraryPath, String instructionSet) {
        final boolean isInAsec;
        if (installOnSd(flags)) {
            /* Apps on SD card are always in ASEC containers. */
            isInAsec = true;
        } else if (installForwardLocked(flags)
                && !fullCodePath.startsWith(mDrmAppPrivateInstallDir.getAbsolutePath())) {
            /*
             * Forward-locked apps are only in ASEC containers if they're the
             * new style
             */
            isInAsec = true;
        } else {
            isInAsec = false;
        }

        if (isInAsec) {
            return new AsecInstallArgs(fullCodePath, fullResourcePath, nativeLibraryPath,
                    instructionSet, installOnSd(flags), installForwardLocked(flags));
        } else {
            return new FileInstallArgs(fullCodePath, fullResourcePath, nativeLibraryPath,
                    instructionSet);
        }
    }

    // Used by package mover
    private InstallArgs createInstallArgs(Uri packageURI, int flags, String pkgName, String dataDir,
            String instructionSet) {
        if (installOnSd(flags) || installForwardLocked(flags)) {
            String cid = getNextCodePath(packageURI.getPath(), pkgName, "/"
                    + AsecInstallArgs.RES_FILE_NAME);
            return new AsecInstallArgs(packageURI, cid, instructionSet, installOnSd(flags),
                    installForwardLocked(flags));
        } else {
            return new FileInstallArgs(packageURI, pkgName, dataDir, instructionSet);
        }
    }

    static abstract class InstallArgs {
        final IPackageInstallObserver observer;
        // Always refers to PackageManager flags only
        final int flags;
        final Uri packageURI;
        final String installerPackageName;
        final ManifestDigest manifestDigest;
        final UserHandle user;
        final String instructionSet;
        final String abiOverride;

        InstallArgs(Uri packageURI, IPackageInstallObserver observer, int flags,
                String installerPackageName, ManifestDigest manifestDigest,
                UserHandle user, String instructionSet, String abiOverride) {
            this.packageURI = packageURI;
            this.flags = flags;
            this.observer = observer;
            this.installerPackageName = installerPackageName;
            this.manifestDigest = manifestDigest;
            this.user = user;
            this.instructionSet = instructionSet;
            this.abiOverride = abiOverride;
        }

        abstract void createCopyFile();
        abstract int copyApk(IMediaContainerService imcs, boolean temp) throws RemoteException;
        abstract int doPreInstall(int status);
        abstract boolean doRename(int status, String pkgName, String oldCodePath);

        abstract int doPostInstall(int status, int uid);
        abstract String getCodePath();
        abstract String getResourcePath();
        abstract String getNativeLibraryPath();
        // Need installer lock especially for dex file removal.
        abstract void cleanUpResourcesLI();
        abstract boolean doPostDeleteLI(boolean delete);
        abstract boolean checkFreeStorage(IMediaContainerService imcs) throws RemoteException;

        /**
         * Called before the source arguments are copied. This is used mostly
         * for MoveParams when it needs to read the source file to put it in the
         * destination.
         */
        int doPreCopy() {
            return PackageManager.INSTALL_SUCCEEDED;
        }

        /**
         * Called after the source arguments are copied. This is used mostly for
         * MoveParams when it needs to read the source file to put it in the
         * destination.
         *
         * @return
         */
        int doPostCopy(int uid) {
            return PackageManager.INSTALL_SUCCEEDED;
        }

        protected boolean isFwdLocked() {
            return (flags & PackageManager.INSTALL_FORWARD_LOCK) != 0;
        }

        UserHandle getUser() {
            return user;
        }
    }

    class FileInstallArgs extends InstallArgs {
        File installDir;
        String codeFileName;
        String resourceFileName;
        String libraryPath;
        boolean created = false;

        FileInstallArgs(InstallParams params) {
            super(params.getPackageUri(), params.observer, params.flags,
                    params.installerPackageName, params.getManifestDigest(),
                    params.getUser(), params.packageInstructionSetOverride,
                    params.packageAbiOverride);
        }

        FileInstallArgs(String fullCodePath, String fullResourcePath, String nativeLibraryPath,
                String instructionSet) {
            super(null, null, 0, null, null, null, instructionSet, null);
            File codeFile = new File(fullCodePath);
            installDir = codeFile.getParentFile();
            codeFileName = fullCodePath;
            resourceFileName = fullResourcePath;
            libraryPath = nativeLibraryPath;
        }

        FileInstallArgs(Uri packageURI, String pkgName, String dataDir, String instructionSet) {
            super(packageURI, null, 0, null, null, null, instructionSet, null);
            installDir = isFwdLocked() ? mDrmAppPrivateInstallDir : mAppInstallDir;
            String apkName = getNextCodePath(null, pkgName, ".apk");
            codeFileName = new File(installDir, apkName + ".apk").getPath();
            resourceFileName = getResourcePathFromCodePath();
            libraryPath = new File(mAppLibInstallDir, pkgName).getPath();
        }

        boolean checkFreeStorage(IMediaContainerService imcs) throws RemoteException {
            final long lowThreshold;

            final DeviceStorageMonitorService dsm = (DeviceStorageMonitorService) ServiceManager
                    .getService(DeviceStorageMonitorService.SERVICE);
            if (dsm == null) {
                Log.w(TAG, "Couldn't get low memory threshold; no free limit imposed");
                lowThreshold = 0L;
            } else {
                if (dsm.isMemoryLow()) {
                    Log.w(TAG, "Memory is reported as being too low; aborting package install");
                    return false;
                }

                lowThreshold = dsm.getMemoryLowThreshold();
            }

            try {
                mContext.grantUriPermission(DEFAULT_CONTAINER_PACKAGE, packageURI,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
                return imcs.checkInternalFreeStorage(packageURI, isFwdLocked(), lowThreshold);
            } finally {
                mContext.revokeUriPermission(packageURI, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
        }

        String getCodePath() {
            return codeFileName;
        }

        void createCopyFile() {
            installDir = isFwdLocked() ? mDrmAppPrivateInstallDir : mAppInstallDir;
            codeFileName = createTempPackageFile(installDir).getPath();
            resourceFileName = getResourcePathFromCodePath();
            libraryPath = getLibraryPathFromCodePath();
            created = true;
        }

        int copyApk(IMediaContainerService imcs, boolean temp) throws RemoteException {
            if (temp) {
                // Generate temp file name
                createCopyFile();
            }
            // Get a ParcelFileDescriptor to write to the output file
            File codeFile = new File(codeFileName);
            if (!created) {
                try {
                    codeFile.createNewFile();
                    // Set permissions
                    if (!setPermissions()) {
                        // Failed setting permissions.
                        return PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE;
                    }
                } catch (IOException e) {
                   Slog.w(TAG, "Failed to create file " + codeFile);
                   return PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE;
                }
            }
            ParcelFileDescriptor out = null;
            try {
                out = ParcelFileDescriptor.open(codeFile, ParcelFileDescriptor.MODE_READ_WRITE);
            } catch (FileNotFoundException e) {
                Slog.e(TAG, "Failed to create file descriptor for : " + codeFileName);
                return PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE;
            }
            // Copy the resource now
            int ret = PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE;
            try {
                mContext.grantUriPermission(DEFAULT_CONTAINER_PACKAGE, packageURI,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
                ret = imcs.copyResource(packageURI, null, out);
            } finally {
                IoUtils.closeQuietly(out);
                mContext.revokeUriPermission(packageURI, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }

            if (isFwdLocked()) {
                final File destResourceFile = new File(getResourcePath());

                // Copy the public files
                try {
                    PackageHelper.extractPublicFiles(codeFileName, destResourceFile);
                } catch (IOException e) {
                    Slog.e(TAG, "Couldn't create a new zip file for the public parts of a"
                            + " forward-locked app.");
                    destResourceFile.delete();
                    return PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE;
                }
            }

            final File nativeLibraryFile = new File(getNativeLibraryPath());
            Slog.i(TAG, "Copying native libraries to " + nativeLibraryFile.getPath());
            if (nativeLibraryFile.exists()) {
                NativeLibraryHelper.removeNativeBinariesFromDirLI(nativeLibraryFile);
                nativeLibraryFile.delete();
            }

            String[] abiList = (abiOverride != null) ?
                    new String[] { abiOverride } : Build.SUPPORTED_ABIS;
            ApkHandle handle = null;
            try {
                handle = ApkHandle.create(codeFile);
                if (Build.SUPPORTED_64_BIT_ABIS.length > 0 &&
                        abiOverride == null &&
                        NativeLibraryHelper.hasRenderscriptBitcode(handle)) {
                    abiList = Build.SUPPORTED_32_BIT_ABIS;
                }

                int copyRet = copyNativeLibrariesForInternalApp(handle, nativeLibraryFile, abiList);
                if (copyRet < 0 && copyRet != PackageManager.NO_NATIVE_LIBRARIES) {
                    return copyRet;
                }
            } catch (IOException e) {
                Slog.e(TAG, "Copying native libraries failed", e);
                ret = PackageManager.INSTALL_FAILED_INTERNAL_ERROR;
            } finally {
                IoUtils.closeQuietly(handle);
            }

            return ret;
        }

        int doPreInstall(int status) {
            if (status != PackageManager.INSTALL_SUCCEEDED) {
                cleanUp();
            }
            return status;
        }

        boolean doRename(int status, final String pkgName, String oldCodePath) {
            if (status != PackageManager.INSTALL_SUCCEEDED) {
                cleanUp();
                return false;
            } else {
                final File oldCodeFile = new File(getCodePath());
                final File oldResourceFile = new File(getResourcePath());
                final File oldLibraryFile = new File(getNativeLibraryPath());

                // Rename APK file based on packageName
                final String apkName = getNextCodePath(oldCodePath, pkgName, ".apk");
                final File newCodeFile = new File(installDir, apkName + ".apk");
                if (!oldCodeFile.renameTo(newCodeFile)) {
                    return false;
                }
                codeFileName = newCodeFile.getPath();

                // Rename public resource file if it's forward-locked.
                final File newResFile = new File(getResourcePathFromCodePath());
                if (isFwdLocked() && !oldResourceFile.renameTo(newResFile)) {
                    return false;
                }
                resourceFileName = newResFile.getPath();

                // Rename library path
                final File newLibraryFile = new File(getLibraryPathFromCodePath());
                if (newLibraryFile.exists()) {
                    NativeLibraryHelper.removeNativeBinariesFromDirLI(newLibraryFile);
                    newLibraryFile.delete();
                }
                if (!oldLibraryFile.renameTo(newLibraryFile)) {
                    Slog.e(TAG, "Cannot rename native library directory "
                            + oldLibraryFile.getPath() + " to " + newLibraryFile.getPath());
                    return false;
                }
                libraryPath = newLibraryFile.getPath();

                // Attempt to set permissions
                if (!setPermissions()) {
                    return false;
                }

                if (!SELinux.restorecon(newCodeFile)) {
                    return false;
                }

                return true;
            }
        }

        int doPostInstall(int status, int uid) {
            if (status != PackageManager.INSTALL_SUCCEEDED) {
                cleanUp();
            }
            return status;
        }

        String getResourcePath() {
            return resourceFileName;
        }

        private String getResourcePathFromCodePath() {
            final String codePath = getCodePath();
            if (isFwdLocked()) {
                final StringBuilder sb = new StringBuilder();

                sb.append(mAppInstallDir.getPath());
                sb.append('/');
                sb.append(getApkName(codePath));
                sb.append(".zip");

                /*
                 * If our APK is a temporary file, mark the resource as a
                 * temporary file as well so it can be cleaned up after
                 * catastrophic failure.
                 */
                if (codePath.endsWith(".tmp")) {
                    sb.append(".tmp");
                }

                return sb.toString();
            } else {
                return codePath;
            }
        }

        private String getLibraryPathFromCodePath() {
            return new File(mAppLibInstallDir, getApkName(getCodePath())).getPath();
        }

        @Override
        String getNativeLibraryPath() {
            if (libraryPath == null) {
                libraryPath = getLibraryPathFromCodePath();
            }
            return libraryPath;
        }

        private boolean cleanUp() {
            boolean ret = true;
            String sourceDir = getCodePath();
            String publicSourceDir = getResourcePath();
            if (sourceDir != null) {
                File sourceFile = new File(sourceDir);
                if (!sourceFile.exists()) {
                    Slog.w(TAG, "Package source " + sourceDir + " does not exist.");
                    ret = false;
                }
                // Delete application's code and resources
                sourceFile.delete();
            }
            if (publicSourceDir != null && !publicSourceDir.equals(sourceDir)) {
                final File publicSourceFile = new File(publicSourceDir);
                if (!publicSourceFile.exists()) {
                    Slog.w(TAG, "Package public source " + publicSourceFile + " does not exist.");
                }
                if (publicSourceFile.exists()) {
                    publicSourceFile.delete();
                }
            }

            if (libraryPath != null) {
                File nativeLibraryFile = new File(libraryPath);
                NativeLibraryHelper.removeNativeBinariesFromDirLI(nativeLibraryFile);
                if (!nativeLibraryFile.delete()) {
                    Slog.w(TAG, "Couldn't delete native library directory " + libraryPath);
                }
            }

            return ret;
        }

        void cleanUpResourcesLI() {
            String sourceDir = getCodePath();
            if (cleanUp()) {
                if (instructionSet == null) {
                    throw new IllegalStateException("instructionSet == null");
                }
                final String dexCodeInstructionSet = getDexCodeInstructionSet(instructionSet);
                int retCode = mInstaller.rmdex(sourceDir, dexCodeInstructionSet);
                if (retCode < 0) {
                    Slog.w(TAG, "Couldn't remove dex file for package: "
                            +  " at location "
                            + sourceDir + ", retcode=" + retCode);
                    // we don't consider this to be a failure of the core package deletion
                }
            }
        }

        private boolean setPermissions() {
            // TODO Do this in a more elegant way later on. for now just a hack
            if (!isFwdLocked()) {
                final int filePermissions =
                    FileUtils.S_IRUSR|FileUtils.S_IWUSR|FileUtils.S_IRGRP
                    |FileUtils.S_IROTH;
                int retCode = FileUtils.setPermissions(getCodePath(), filePermissions, -1, -1);
                if (retCode != 0) {
                    Slog.e(TAG, "Couldn't set new package file permissions for " +
                            getCodePath()
                            + ". The return code was: " + retCode);
                    // TODO Define new internal error
                    return false;
                }
                return true;
            }
            return true;
        }

        boolean doPostDeleteLI(boolean delete) {
            // XXX err, shouldn't we respect the delete flag?
            cleanUpResourcesLI();
            return true;
        }
    }

    private boolean isAsecExternal(String cid) {
        final String asecPath = PackageHelper.getSdFilesystem(cid);
        return !asecPath.startsWith(mAsecInternalPath);
    }

    /**
     * Extract the MountService "container ID" from the full code path of an
     * .apk.
     */
    static String cidFromCodePath(String fullCodePath) {
        int eidx = fullCodePath.lastIndexOf("/");
        String subStr1 = fullCodePath.substring(0, eidx);
        int sidx = subStr1.lastIndexOf("/");
        return subStr1.substring(sidx+1, eidx);
    }

    class AsecInstallArgs extends InstallArgs {
        static final String RES_FILE_NAME = "pkg.apk";
        static final String PUBLIC_RES_FILE_NAME = "res.zip";

        String cid;
        String packagePath;
        String resourcePath;
        String libraryPath;

        AsecInstallArgs(InstallParams params) {
            super(params.getPackageUri(), params.observer, params.flags,
                    params.installerPackageName, params.getManifestDigest(),
                    params.getUser(), params.packageInstructionSetOverride,
                    params.packageAbiOverride);
        }

        AsecInstallArgs(String fullCodePath, String fullResourcePath, String nativeLibraryPath,
                String instructionSet, boolean isExternal, boolean isForwardLocked) {
            super(null, null, (isExternal ? PackageManager.INSTALL_EXTERNAL : 0)
                    | (isForwardLocked ? PackageManager.INSTALL_FORWARD_LOCK : 0),
                    null, null, null, instructionSet, null);
            // Extract cid from fullCodePath
            int eidx = fullCodePath.lastIndexOf("/");
            String subStr1 = fullCodePath.substring(0, eidx);
            int sidx = subStr1.lastIndexOf("/");
            cid = subStr1.substring(sidx+1, eidx);
            setCachePath(subStr1);
        }

        AsecInstallArgs(String cid, String instructionSet, boolean isForwardLocked) {
            super(null, null, (isAsecExternal(cid) ? PackageManager.INSTALL_EXTERNAL : 0)
                    | (isForwardLocked ? PackageManager.INSTALL_FORWARD_LOCK : 0),
                    null, null, null, instructionSet, null);
            this.cid = cid;
            setCachePath(PackageHelper.getSdDir(cid));
        }

        AsecInstallArgs(Uri packageURI, String cid, String instructionSet,
                boolean isExternal, boolean isForwardLocked) {
            super(packageURI, null, (isExternal ? PackageManager.INSTALL_EXTERNAL : 0)
                    | (isForwardLocked ? PackageManager.INSTALL_FORWARD_LOCK : 0),
                    null, null, null, instructionSet, null);
            this.cid = cid;
        }

        void createCopyFile() {
            cid = getTempContainerId();
        }

        boolean checkFreeStorage(IMediaContainerService imcs) throws RemoteException {
            try {
                mContext.grantUriPermission(DEFAULT_CONTAINER_PACKAGE, packageURI,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
                return imcs.checkExternalFreeStorage(packageURI, isFwdLocked(), abiOverride);
            } finally {
                mContext.revokeUriPermission(packageURI, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
        }

        private final boolean isExternal() {
            return (flags & PackageManager.INSTALL_EXTERNAL) != 0;
        }

        int copyApk(IMediaContainerService imcs, boolean temp) throws RemoteException {
            if (temp) {
                createCopyFile();
            } else {
                /*
                 * Pre-emptively destroy the container since it's destroyed if
                 * copying fails due to it existing anyway.
                 */
                PackageHelper.destroySdDir(cid);
            }

            final String newCachePath;
            try {
                mContext.grantUriPermission(DEFAULT_CONTAINER_PACKAGE, packageURI,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
                newCachePath = imcs.copyResourceToContainer(packageURI, cid, getEncryptKey(),
                        RES_FILE_NAME, PUBLIC_RES_FILE_NAME, isExternal(), isFwdLocked(),
                        abiOverride);
            } finally {
                mContext.revokeUriPermission(packageURI, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }

            if (newCachePath != null) {
                setCachePath(newCachePath);
                return PackageManager.INSTALL_SUCCEEDED;
            } else {
                return PackageManager.INSTALL_FAILED_CONTAINER_ERROR;
            }
        }

        @Override
        String getCodePath() {
            return packagePath;
        }

        @Override
        String getResourcePath() {
            return resourcePath;
        }

        @Override
        String getNativeLibraryPath() {
            return libraryPath;
        }

        int doPreInstall(int status) {
            if (status != PackageManager.INSTALL_SUCCEEDED) {
                // Destroy container
                PackageHelper.destroySdDir(cid);
            } else {
                boolean mounted = PackageHelper.isContainerMounted(cid);
                if (!mounted) {
                    String newCachePath = PackageHelper.mountSdDir(cid, getEncryptKey(),
                            Process.SYSTEM_UID);
                    if (newCachePath != null) {
                        setCachePath(newCachePath);
                    } else {
                        return PackageManager.INSTALL_FAILED_CONTAINER_ERROR;
                    }
                }
            }
            return status;
        }

        boolean doRename(int status, final String pkgName,
                String oldCodePath) {
            String newCacheId = getNextCodePath(oldCodePath, pkgName, "/" + RES_FILE_NAME);
            String newCachePath = null;
            if (PackageHelper.isContainerMounted(cid)) {
                // Unmount the container
                if (!PackageHelper.unMountSdDir(cid)) {
                    Slog.i(TAG, "Failed to unmount " + cid + " before renaming");
                    return false;
                }
            }
            if (!PackageHelper.renameSdDir(cid, newCacheId)) {
                Slog.e(TAG, "Failed to rename " + cid + " to " + newCacheId +
                        " which might be stale. Will try to clean up.");
                // Clean up the stale container and proceed to recreate.
                if (!PackageHelper.destroySdDir(newCacheId)) {
                    Slog.e(TAG, "Very strange. Cannot clean up stale container " + newCacheId);
                    return false;
                }
                // Successfully cleaned up stale container. Try to rename again.
                if (!PackageHelper.renameSdDir(cid, newCacheId)) {
                    Slog.e(TAG, "Failed to rename " + cid + " to " + newCacheId
                            + " inspite of cleaning it up.");
                    return false;
                }
            }
            if (!PackageHelper.isContainerMounted(newCacheId)) {
                Slog.w(TAG, "Mounting container " + newCacheId);
                newCachePath = PackageHelper.mountSdDir(newCacheId,
                        getEncryptKey(), Process.SYSTEM_UID);
            } else {
                newCachePath = PackageHelper.getSdDir(newCacheId);
            }
            if (newCachePath == null) {
                Slog.w(TAG, "Failed to get cache path for  " + newCacheId);
                return false;
            }
            Log.i(TAG, "Succesfully renamed " + cid +
                    " to " + newCacheId +
                    " at new path: " + newCachePath);
            cid = newCacheId;
            setCachePath(newCachePath);
            return true;
        }

        private void setCachePath(String newCachePath) {
            File cachePath = new File(newCachePath);
            libraryPath = new File(cachePath, LIB_DIR_NAME).getPath();
            packagePath = new File(cachePath, RES_FILE_NAME).getPath();

            if (isFwdLocked()) {
                resourcePath = new File(cachePath, PUBLIC_RES_FILE_NAME).getPath();
            } else {
                resourcePath = packagePath;
            }
        }

        int doPostInstall(int status, int uid) {
            if (status != PackageManager.INSTALL_SUCCEEDED) {
                cleanUp();
            } else {
                final int groupOwner;
                final String protectedFile;
                if (isFwdLocked()) {
                    groupOwner = UserHandle.getSharedAppGid(uid);
                    protectedFile = RES_FILE_NAME;
                } else {
                    groupOwner = -1;
                    protectedFile = null;
                }

                if (uid < Process.FIRST_APPLICATION_UID
                        || !PackageHelper.fixSdPermissions(cid, groupOwner, protectedFile)) {
                    Slog.e(TAG, "Failed to finalize " + cid);
                    PackageHelper.destroySdDir(cid);
                    return PackageManager.INSTALL_FAILED_CONTAINER_ERROR;
                }

                boolean mounted = PackageHelper.isContainerMounted(cid);
                if (!mounted) {
                    PackageHelper.mountSdDir(cid, getEncryptKey(), Process.myUid());
                }
            }
            return status;
        }

        private void cleanUp() {
            if (DEBUG_SD_INSTALL) Slog.i(TAG, "cleanUp");

            // Destroy secure container
            PackageHelper.destroySdDir(cid);
        }

        void cleanUpResourcesLI() {
            String sourceFile = getCodePath();
            // Remove dex file
            if (instructionSet == null) {
                throw new IllegalStateException("instructionSet == null");
            }
            final String dexCodeInstructionSet = getDexCodeInstructionSet(instructionSet);
            int retCode = mInstaller.rmdex(sourceFile, dexCodeInstructionSet);
            if (retCode < 0) {
                Slog.w(TAG, "Couldn't remove dex file for package: "
                        + " at location "
                        + sourceFile.toString() + ", retcode=" + retCode);
                // we don't consider this to be a failure of the core package deletion
            }
            cleanUp();
        }

        boolean matchContainer(String app) {
            if (cid.startsWith(app)) {
                return true;
            }
            return false;
        }

        String getPackageName() {
            return getAsecPackageName(cid);
        }

        boolean doPostDeleteLI(boolean delete) {
            boolean ret = false;
            boolean mounted = PackageHelper.isContainerMounted(cid);
            if (mounted) {
                // Unmount first
                ret = PackageHelper.unMountSdDir(cid);
            }
            if (ret && delete) {
                cleanUpResourcesLI();
            }
            return ret;
        }

        @Override
        int doPreCopy() {
            if (isFwdLocked()) {
                if (!PackageHelper.fixSdPermissions(cid,
                        getPackageUid(DEFAULT_CONTAINER_PACKAGE, 0), RES_FILE_NAME)) {
                    return PackageManager.INSTALL_FAILED_CONTAINER_ERROR;
                }
            }

            return PackageManager.INSTALL_SUCCEEDED;
        }

        @Override
        int doPostCopy(int uid) {
            if (isFwdLocked()) {
                if (uid < Process.FIRST_APPLICATION_UID
                        || !PackageHelper.fixSdPermissions(cid, UserHandle.getSharedAppGid(uid),
                                RES_FILE_NAME)) {
                    Slog.e(TAG, "Failed to finalize " + cid);
                    PackageHelper.destroySdDir(cid);
                    return PackageManager.INSTALL_FAILED_CONTAINER_ERROR;
                }
            }

            return PackageManager.INSTALL_SUCCEEDED;
        }
    };

    static String getAsecPackageName(String packageCid) {
        int idx = packageCid.lastIndexOf("-");
        if (idx == -1) {
            return packageCid;
        }
        return packageCid.substring(0, idx);
    }

    // Utility method used to create code paths based on package name and available index.
    private static String getNextCodePath(String oldCodePath, String prefix, String suffix) {
        String idxStr = "";
        int idx = 1;
        // Fall back to default value of idx=1 if prefix is not
        // part of oldCodePath
        if (oldCodePath != null) {
            String subStr = oldCodePath;
            // Drop the suffix right away
            if (subStr.endsWith(suffix)) {
                subStr = subStr.substring(0, subStr.length() - suffix.length());
            }
            // If oldCodePath already contains prefix find out the
            // ending index to either increment or decrement.
            int sidx = subStr.lastIndexOf(prefix);
            if (sidx != -1) {
                subStr = subStr.substring(sidx + prefix.length());
                if (subStr != null) {
                    if (subStr.startsWith(INSTALL_PACKAGE_SUFFIX)) {
                        subStr = subStr.substring(INSTALL_PACKAGE_SUFFIX.length());
                    }
                    try {
                        idx = Integer.parseInt(subStr);
                        if (idx <= 1) {
                            idx++;
                        } else {
                            idx--;
                        }
                    } catch(NumberFormatException e) {
                    }
                }
            }
        }
        idxStr = INSTALL_PACKAGE_SUFFIX + Integer.toString(idx);
        return prefix + idxStr;
    }

    // Utility method used to ignore ADD/REMOVE events
    // by directory observer.
    private static boolean ignoreCodePath(String fullPathStr) {
        String apkName = getApkName(fullPathStr);
        int idx = apkName.lastIndexOf(INSTALL_PACKAGE_SUFFIX);
        if (idx != -1 && ((idx+1) < apkName.length())) {
            // Make sure the package ends with a numeral
            String version = apkName.substring(idx+1);
            try {
                Integer.parseInt(version);
                return true;
            } catch (NumberFormatException e) {}
        }
        return false;
    }
    
    // Utility method that returns the relative package path with respect
    // to the installation directory. Like say for /data/data/com.test-1.apk
    // string com.test-1 is returned.
    static String getApkName(String codePath) {
        if (codePath == null) {
            return null;
        }
        int sidx = codePath.lastIndexOf("/");
        int eidx = codePath.lastIndexOf(".");
        if (eidx == -1) {
            eidx = codePath.length();
        } else if (eidx == 0) {
            Slog.w(TAG, " Invalid code path, "+ codePath + " Not a valid apk name");
            return null;
        }
        return codePath.substring(sidx+1, eidx);
    }

    class PackageInstalledInfo {
        String name;
        int uid;
        // The set of users that originally had this package installed.
        int[] origUsers;
        // The set of users that now have this package installed.
        int[] newUsers;
        PackageParser.Package pkg;
        int returnCode;
        PackageRemovedInfo removedInfo;
    }

    /*
     * Install a non-existing package.
     */
    private void installNewPackageLI(PackageParser.Package pkg,
            int parseFlags, int scanMode, UserHandle user,
            String installerPackageName, PackageInstalledInfo res, String abiOverride) {
        // Remember this for later, in case we need to rollback this install
        String pkgName = pkg.packageName;

        if (DEBUG_INSTALL) Slog.d(TAG, "installNewPackageLI: " + pkg);
        boolean dataDirExists = getDataPathForPackage(pkg.packageName, 0).exists();
        synchronized(mPackages) {
            if (mSettings.mRenamedPackages.containsKey(pkgName)) {
                // A package with the same name is already installed, though
                // it has been renamed to an older name.  The package we
                // are trying to install should be installed as an update to
                // the existing one, but that has not been requested, so bail.
                Slog.w(TAG, "Attempt to re-install " + pkgName
                        + " without first uninstalling package running as "
                        + mSettings.mRenamedPackages.get(pkgName));
                res.returnCode = PackageManager.INSTALL_FAILED_ALREADY_EXISTS;
                return;
            }
            if (mPackages.containsKey(pkgName) || mAppDirs.containsKey(pkg.mPath)) {
                // Don't allow installation over an existing package with the same name.
                Slog.w(TAG, "Attempt to re-install " + pkgName
                        + " without first uninstalling.");
                res.returnCode = PackageManager.INSTALL_FAILED_ALREADY_EXISTS;
                return;
            }
        }
        mLastScanError = PackageManager.INSTALL_SUCCEEDED;
        PackageParser.Package newPackage = scanPackageLI(pkg, parseFlags, scanMode,
                System.currentTimeMillis(), user, abiOverride);
        if (newPackage == null) {
            Slog.w(TAG, "Package couldn't be installed in " + pkg.mPath);
            if ((res.returnCode=mLastScanError) == PackageManager.INSTALL_SUCCEEDED) {
                res.returnCode = PackageManager.INSTALL_FAILED_INVALID_APK;
            }
        } else {
            updateSettingsLI(newPackage,
                    installerPackageName,
                    null, null,
                    res);
            // delete the partially installed application. the data directory will have to be
            // restored if it was already existing
            if (res.returnCode != PackageManager.INSTALL_SUCCEEDED) {
                // remove package from internal structures.  Note that we want deletePackageX to
                // delete the package data and cache directories that it created in
                // scanPackageLocked, unless those directories existed before we even tried to
                // install.
                deletePackageLI(pkgName, UserHandle.ALL, false, null, null,
                        dataDirExists ? PackageManager.DELETE_KEEP_DATA : 0,
                                res.removedInfo, true);
            }
        }
    }

    private void replacePackageLI(PackageParser.Package pkg,
            int parseFlags, int scanMode, UserHandle user,
            String installerPackageName, PackageInstalledInfo res, String abiOverride) {

        PackageParser.Package oldPackage;
        String pkgName = pkg.packageName;
        int[] allUsers;
        boolean[] perUserInstalled;

        // First find the old package info and check signatures
        synchronized(mPackages) {
            oldPackage = mPackages.get(pkgName);
            if (DEBUG_INSTALL) Slog.d(TAG, "replacePackageLI: new=" + pkg + ", old=" + oldPackage);
            if (compareSignatures(oldPackage.mSignatures, pkg.mSignatures)
                    != PackageManager.SIGNATURE_MATCH) {
                Slog.w(TAG, "New package has a different signature: " + pkgName);
                res.returnCode = PackageManager.INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES;
                return;
            }

            // In case of rollback, remember per-user/profile install state
            PackageSetting ps = mSettings.mPackages.get(pkgName);
            allUsers = sUserManager.getUserIds();
            perUserInstalled = new boolean[allUsers.length];
            for (int i = 0; i < allUsers.length; i++) {
                perUserInstalled[i] = ps != null ? ps.getInstalled(allUsers[i]) : false;
            }
        }
        boolean sysPkg = (isSystemApp(oldPackage));
        if (sysPkg) {
            replaceSystemPackageLI(oldPackage, pkg, parseFlags, scanMode,
                    user, allUsers, perUserInstalled, installerPackageName, res,
                    abiOverride);
        } else {
            replaceNonSystemPackageLI(oldPackage, pkg, parseFlags, scanMode,
                    user, allUsers, perUserInstalled, installerPackageName, res,
                    abiOverride);
        }
    }

    private void replaceNonSystemPackageLI(PackageParser.Package deletedPackage,
            PackageParser.Package pkg, int parseFlags, int scanMode, UserHandle user,
            int[] allUsers, boolean[] perUserInstalled,
            String installerPackageName, PackageInstalledInfo res, String abiOverride) {
        PackageParser.Package newPackage = null;
        String pkgName = deletedPackage.packageName;
        boolean deletedPkg = true;
        boolean updatedSettings = false;

        if (DEBUG_INSTALL) Slog.d(TAG, "replaceNonSystemPackageLI: new=" + pkg + ", old="
                + deletedPackage);
        long origUpdateTime;
        if (pkg.mExtras != null) {
            origUpdateTime = ((PackageSetting)pkg.mExtras).lastUpdateTime;
        } else {
            origUpdateTime = 0;
        }

        // First delete the existing package while retaining the data directory
        if (!deletePackageLI(pkgName, null, true, null, null, PackageManager.DELETE_KEEP_DATA,
                res.removedInfo, true)) {
            // If the existing package wasn't successfully deleted
            res.returnCode = PackageManager.INSTALL_FAILED_REPLACE_COULDNT_DELETE;
            deletedPkg = false;
        } else {
            // Successfully deleted the old package. Now proceed with re-installation
            mLastScanError = PackageManager.INSTALL_SUCCEEDED;
            newPackage = scanPackageLI(pkg, parseFlags, scanMode | SCAN_UPDATE_TIME,
                    System.currentTimeMillis(), user, abiOverride);
            if (newPackage == null) {
                Slog.w(TAG, "Package couldn't be installed in " + pkg.mPath);
                if ((res.returnCode=mLastScanError) == PackageManager.INSTALL_SUCCEEDED) {
                    res.returnCode = PackageManager.INSTALL_FAILED_INVALID_APK;
                }
            } else {
                updateSettingsLI(newPackage,
                        installerPackageName,
                        allUsers, perUserInstalled,
                        res);
                updatedSettings = true;
            }
        }

        if (res.returnCode != PackageManager.INSTALL_SUCCEEDED) {
            // remove package from internal structures.  Note that we want deletePackageX to
            // delete the package data and cache directories that it created in
            // scanPackageLocked, unless those directories existed before we even tried to
            // install.
            if(updatedSettings) {
                if (DEBUG_INSTALL) Slog.d(TAG, "Install failed, rolling pack: " + pkgName);
                deletePackageLI(
                        pkgName, null, true, allUsers, perUserInstalled,
                        PackageManager.DELETE_KEEP_DATA,
                                res.removedInfo, true);
            }
            // Since we failed to install the new package we need to restore the old
            // package that we deleted.
            if (deletedPkg) {
                if (DEBUG_INSTALL) Slog.d(TAG, "Install failed, reinstalling: " + deletedPackage);
                File restoreFile = new File(deletedPackage.mPath);
                // Parse old package
                boolean oldOnSd = isExternal(deletedPackage);
                int oldParseFlags  = mDefParseFlags | PackageParser.PARSE_CHATTY |
                        (isForwardLocked(deletedPackage) ? PackageParser.PARSE_FORWARD_LOCK : 0) |
                        (oldOnSd ? PackageParser.PARSE_ON_SDCARD : 0);
                int oldScanMode = (oldOnSd ? 0 : SCAN_MONITOR) | SCAN_UPDATE_SIGNATURE
                        | SCAN_UPDATE_TIME;
                if (scanPackageLI(restoreFile, oldParseFlags, oldScanMode,
                        origUpdateTime, null, null) == null) {
                    Slog.e(TAG, "Failed to restore package : " + pkgName + " after failed upgrade");
                    return;
                }
                // Restore of old package succeeded. Update permissions.
                // writer
                synchronized (mPackages) {
                    updatePermissionsLPw(deletedPackage.packageName, deletedPackage,
                            UPDATE_PERMISSIONS_ALL);
                    // can downgrade to reader
                    mSettings.writeLPr();
                }
                Slog.i(TAG, "Successfully restored package : " + pkgName + " after failed upgrade");
            }
        }
    }

    private void replaceSystemPackageLI(PackageParser.Package deletedPackage,
            PackageParser.Package pkg, int parseFlags, int scanMode, UserHandle user,
            int[] allUsers, boolean[] perUserInstalled,
            String installerPackageName, PackageInstalledInfo res, String abiOverride) {
        if (DEBUG_INSTALL) Slog.d(TAG, "replaceSystemPackageLI: new=" + pkg
                + ", old=" + deletedPackage);
        PackageParser.Package newPackage = null;
        boolean updatedSettings = false;
        parseFlags |= PackageManager.INSTALL_REPLACE_EXISTING |
                PackageParser.PARSE_IS_SYSTEM;
        if ((deletedPackage.applicationInfo.flags&ApplicationInfo.FLAG_PRIVILEGED) != 0) {
            parseFlags |= PackageParser.PARSE_IS_PRIVILEGED;
        }
        String packageName = deletedPackage.packageName;
        res.returnCode = PackageManager.INSTALL_FAILED_REPLACE_COULDNT_DELETE;
        if (packageName == null) {
            Slog.w(TAG, "Attempt to delete null packageName.");
            return;
        }
        PackageParser.Package oldPkg;
        PackageSetting oldPkgSetting;
        // reader
        synchronized (mPackages) {
            oldPkg = mPackages.get(packageName);
            oldPkgSetting = mSettings.mPackages.get(packageName);
            if((oldPkg == null) || (oldPkg.applicationInfo == null) ||
                    (oldPkgSetting == null)) {
                Slog.w(TAG, "Couldn't find package:"+packageName+" information");
                return;
            }
        }

        killApplication(packageName, oldPkg.applicationInfo.uid, "replace sys pkg");

        res.removedInfo.uid = oldPkg.applicationInfo.uid;
        res.removedInfo.removedPackage = packageName;
        // Remove existing system package
        removePackageLI(oldPkgSetting, true);
        // writer
        synchronized (mPackages) {
            if (!mSettings.disableSystemPackageLPw(packageName) && deletedPackage != null) {
                // We didn't need to disable the .apk as a current system package,
                // which means we are replacing another update that is already
                // installed.  We need to make sure to delete the older one's .apk.
                res.removedInfo.args = createInstallArgs(0,
                        deletedPackage.applicationInfo.sourceDir,
                        deletedPackage.applicationInfo.publicSourceDir,
                        deletedPackage.applicationInfo.nativeLibraryDir,
                        getAppInstructionSet(deletedPackage.applicationInfo));
            } else {
                res.removedInfo.args = null;
            }
        }
        
        // Successfully disabled the old package. Now proceed with re-installation
        mLastScanError = PackageManager.INSTALL_SUCCEEDED;
        pkg.applicationInfo.flags |= ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
        newPackage = scanPackageLI(pkg, parseFlags, scanMode, 0, user, abiOverride);
        if (newPackage == null) {
            Slog.w(TAG, "Package couldn't be installed in " + pkg.mPath);
            if ((res.returnCode=mLastScanError) == PackageManager.INSTALL_SUCCEEDED) {
                res.returnCode = PackageManager.INSTALL_FAILED_INVALID_APK;
            }
        } else {
            if (newPackage.mExtras != null) {
                final PackageSetting newPkgSetting = (PackageSetting)newPackage.mExtras;
                newPkgSetting.firstInstallTime = oldPkgSetting.firstInstallTime;
                newPkgSetting.lastUpdateTime = System.currentTimeMillis();
            }
            updateSettingsLI(newPackage, installerPackageName, allUsers, perUserInstalled, res);
            updatedSettings = true;
        }

        if (res.returnCode != PackageManager.INSTALL_SUCCEEDED) {
            // Re installation failed. Restore old information
            // Remove new pkg information
            if (newPackage != null) {
                removeInstalledPackageLI(newPackage, true);
            }
            // Add back the old system package
            scanPackageLI(oldPkg, parseFlags, SCAN_MONITOR | SCAN_UPDATE_SIGNATURE, 0, user, null);
            // Restore the old system information in Settings
            synchronized(mPackages) {
                if (updatedSettings) {
                    mSettings.enableSystemPackageLPw(packageName);
                    mSettings.setInstallerPackageName(packageName,
                            oldPkgSetting.installerPackageName);
                }
                mSettings.writeLPr();
            }
        }
    }

    // Utility method used to move dex files during install.
    private int moveDexFilesLI(PackageParser.Package newPackage) {
        if ((newPackage.applicationInfo.flags&ApplicationInfo.FLAG_HAS_CODE) != 0) {
            final String instructionSet = getAppInstructionSet(newPackage.applicationInfo);
            final String dexCodeInstructionSet = getDexCodeInstructionSet(instructionSet);
            int retCode = mInstaller.movedex(newPackage.mScanPath, newPackage.mPath,
                                             dexCodeInstructionSet);
            if (retCode != 0) {
                /*
                 * Programs may be lazily run through dexopt, so the
                 * source may not exist. However, something seems to
                 * have gone wrong, so note that dexopt needs to be
                 * run again and remove the source file. In addition,
                 * remove the target to make sure there isn't a stale
                 * file from a previous version of the package.
                 */
                newPackage.mDexOptNeeded = true;
                mInstaller.rmdex(newPackage.mScanPath, dexCodeInstructionSet);
                mInstaller.rmdex(newPackage.mPath, dexCodeInstructionSet);
            }
        }
        return PackageManager.INSTALL_SUCCEEDED;
    }

    private void updateSettingsLI(PackageParser.Package newPackage, String installerPackageName,
            int[] allUsers, boolean[] perUserInstalled,
            PackageInstalledInfo res) {
        String pkgName = newPackage.packageName;
        synchronized (mPackages) {
            //write settings. the installStatus will be incomplete at this stage.
            //note that the new package setting would have already been
            //added to mPackages. It hasn't been persisted yet.
            mSettings.setInstallStatus(pkgName, PackageSettingBase.PKG_INSTALL_INCOMPLETE);
            mSettings.writeLPr();
        }

        if ((res.returnCode = moveDexFilesLI(newPackage))
                != PackageManager.INSTALL_SUCCEEDED) {
            // Discontinue if moving dex files failed.
            return;
        }

        if (DEBUG_INSTALL) Slog.d(TAG, "New package installed in " + newPackage.mPath);

        synchronized (mPackages) {
            updatePermissionsLPw(newPackage.packageName, newPackage,
                    UPDATE_PERMISSIONS_REPLACE_PKG | (newPackage.permissions.size() > 0
                            ? UPDATE_PERMISSIONS_ALL : 0));
            // For system-bundled packages, we assume that installing an upgraded version
            // of the package implies that the user actually wants to run that new code,
            // so we enable the package.
            if (isSystemApp(newPackage)) {
                // NB: implicit assumption that system package upgrades apply to all users
                if (DEBUG_INSTALL) {
                    Slog.d(TAG, "Implicitly enabling system package on upgrade: " + pkgName);
                }
                PackageSetting ps = mSettings.mPackages.get(pkgName);
                if (ps != null) {
                    if (res.origUsers != null) {
                        for (int userHandle : res.origUsers) {
                            ps.setEnabled(COMPONENT_ENABLED_STATE_DEFAULT,
                                    userHandle, installerPackageName);
                        }
                    }
                    // Also convey the prior install/uninstall state
                    if (allUsers != null && perUserInstalled != null) {
                        for (int i = 0; i < allUsers.length; i++) {
                            if (DEBUG_INSTALL) {
                                Slog.d(TAG, "    user " + allUsers[i]
                                        + " => " + perUserInstalled[i]);
                            }
                            ps.setInstalled(perUserInstalled[i], allUsers[i]);
                        }
                        // these install state changes will be persisted in the
                        // upcoming call to mSettings.writeLPr().
                    }
                }
            }
            res.name = pkgName;
            res.uid = newPackage.applicationInfo.uid;
            res.pkg = newPackage;
            mSettings.setInstallStatus(pkgName, PackageSettingBase.PKG_INSTALL_COMPLETE);
            mSettings.setInstallerPackageName(pkgName, installerPackageName);
            res.returnCode = PackageManager.INSTALL_SUCCEEDED;
            //to update install status
            mSettings.writeLPr();
        }
    }

    private void installPackageLI(InstallArgs args,
            boolean newInstall, PackageInstalledInfo res) {
        int pFlags = args.flags;
        String installerPackageName = args.installerPackageName;
        File tmpPackageFile = new File(args.getCodePath());
        boolean forwardLocked = ((pFlags & PackageManager.INSTALL_FORWARD_LOCK) != 0);
        boolean onSd = ((pFlags & PackageManager.INSTALL_EXTERNAL) != 0);
        boolean replace = false;
        int scanMode = (onSd ? 0 : SCAN_MONITOR) | SCAN_FORCE_DEX | SCAN_UPDATE_SIGNATURE
                | (newInstall ? SCAN_NEW_INSTALL : 0);
        // Result object to be returned
        res.returnCode = PackageManager.INSTALL_SUCCEEDED;

        if (DEBUG_INSTALL) Slog.d(TAG, "installPackageLI: path=" + tmpPackageFile);
        // Retrieve PackageSettings and parse package
        int parseFlags = mDefParseFlags | PackageParser.PARSE_CHATTY
                | (forwardLocked ? PackageParser.PARSE_FORWARD_LOCK : 0)
                | (onSd ? PackageParser.PARSE_ON_SDCARD : 0);
        PackageParser pp = new PackageParser(tmpPackageFile.getPath());
        pp.setSeparateProcesses(mSeparateProcesses);
        final PackageParser.Package pkg = pp.parsePackage(tmpPackageFile,
                null, mMetrics, parseFlags);
        if (pkg == null) {
            res.returnCode = pp.getParseError();
            return;
        }
        String pkgName = res.name = pkg.packageName;
        if ((pkg.applicationInfo.flags&ApplicationInfo.FLAG_TEST_ONLY) != 0) {
            if ((pFlags&PackageManager.INSTALL_ALLOW_TEST) == 0) {
                res.returnCode = PackageManager.INSTALL_FAILED_TEST_ONLY;
                return;
            }
        }
        if (GET_CERTIFICATES && !pp.collectCertificates(pkg, parseFlags)) {
            res.returnCode = pp.getParseError();
            return;
        }

        /* If the installer passed in a manifest digest, compare it now. */
        if (args.manifestDigest != null) {
            if (DEBUG_INSTALL) {
                final String parsedManifest = pkg.manifestDigest == null ? "null"
                        : pkg.manifestDigest.toString();
                Slog.d(TAG, "Comparing manifests: " + args.manifestDigest.toString() + " vs. "
                        + parsedManifest);
            }

            if (!args.manifestDigest.equals(pkg.manifestDigest)) {
                res.returnCode = PackageManager.INSTALL_FAILED_PACKAGE_CHANGED;
                return;
            }
        } else if (DEBUG_INSTALL) {
            final String parsedManifest = pkg.manifestDigest == null
                    ? "null" : pkg.manifestDigest.toString();
            Slog.d(TAG, "manifestDigest was not present, but parser got: " + parsedManifest);
        }

        // Get rid of all references to package scan path via parser.
        pp = null;
        String oldCodePath = null;
        boolean systemApp = false;
        synchronized (mPackages) {
            // Check if installing already existing package
            if ((pFlags&PackageManager.INSTALL_REPLACE_EXISTING) != 0) {
                String oldName = mSettings.mRenamedPackages.get(pkgName);
                if (pkg.mOriginalPackages != null
                        && pkg.mOriginalPackages.contains(oldName)
                        && mPackages.containsKey(oldName)) {
                    // This package is derived from an original package,
                    // and this device has been updating from that original
                    // name.  We must continue using the original name, so
                    // rename the new package here.
                    pkg.setPackageName(oldName);
                    pkgName = pkg.packageName;
                    replace = true;
                    if (DEBUG_INSTALL) Slog.d(TAG, "Replacing existing renamed package: oldName="
                            + oldName + " pkgName=" + pkgName);
                } else if (mPackages.containsKey(pkgName)) {
                    // This package, under its official name, already exists
                    // on the device; we should replace it.
                    replace = true;
                    if (DEBUG_INSTALL) Slog.d(TAG, "Replace existing pacakge: " + pkgName);
                }
            }
            PackageSetting ps = mSettings.mPackages.get(pkgName);
            if (ps != null) {
                if (DEBUG_INSTALL) Slog.d(TAG, "Existing package: " + ps);
                oldCodePath = mSettings.mPackages.get(pkgName).codePathString;
                if (ps.pkg != null && ps.pkg.applicationInfo != null) {
                    systemApp = (ps.pkg.applicationInfo.flags &
                            ApplicationInfo.FLAG_SYSTEM) != 0;
                }
                res.origUsers = ps.queryInstalledUsers(sUserManager.getUserIds(), true);
            }
        }

        if (systemApp && onSd) {
            // Disable updates to system apps on sdcard
            Slog.w(TAG, "Cannot install updates to system apps on sdcard");
            res.returnCode = PackageManager.INSTALL_FAILED_INVALID_INSTALL_LOCATION;
            return;
        }

        if (!args.doRename(res.returnCode, pkgName, oldCodePath)) {
            res.returnCode = PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE;
            return;
        }
        // Set application objects path explicitly after the rename
        setApplicationInfoPaths(pkg, args.getCodePath(), args.getResourcePath());
        pkg.applicationInfo.nativeLibraryDir = args.getNativeLibraryPath();
        if (replace) {
            replacePackageLI(pkg, parseFlags, scanMode, args.user,
                    installerPackageName, res, args.abiOverride);
        } else {
            installNewPackageLI(pkg, parseFlags, scanMode | SCAN_DELETE_DATA_ON_FAILURES, args.user,
                    installerPackageName, res, args.abiOverride);
        }
        synchronized (mPackages) {
            final PackageSetting ps = mSettings.mPackages.get(pkgName);
            if (ps != null) {
                res.newUsers = ps.queryInstalledUsers(sUserManager.getUserIds(), true);
            }
        }
    }

    private static boolean isForwardLocked(PackageParser.Package pkg) {
        return (pkg.applicationInfo.flags & ApplicationInfo.FLAG_FORWARD_LOCK) != 0;
    }


    private boolean isForwardLocked(PackageSetting ps) {
        return (ps.pkgFlags & ApplicationInfo.FLAG_FORWARD_LOCK) != 0;
    }

    private static boolean isExternal(PackageParser.Package pkg) {
        return (pkg.applicationInfo.flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0;
    }

    private static boolean isExternal(PackageSetting ps) {
        return (ps.pkgFlags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0;
    }

    private static boolean isSystemApp(PackageParser.Package pkg) {
        return (pkg.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
    }

    private static boolean isPrivilegedApp(PackageParser.Package pkg) {
        return (pkg.applicationInfo.flags & ApplicationInfo.FLAG_PRIVILEGED) != 0;
    }

    private static boolean isSystemApp(ApplicationInfo info) {
        return (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
    }

    private static boolean isSystemApp(PackageSetting ps) {
        return (ps.pkgFlags & ApplicationInfo.FLAG_SYSTEM) != 0;
    }

    private static boolean isUpdatedSystemApp(PackageSetting ps) {
        return (ps.pkgFlags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
    }

    private static boolean isUpdatedSystemApp(PackageParser.Package pkg) {
        return (pkg.applicationInfo.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
    }

    private int packageFlagsToInstallFlags(PackageSetting ps) {
        int installFlags = 0;
        if (isExternal(ps)) {
            installFlags |= PackageManager.INSTALL_EXTERNAL;
        }
        if (isForwardLocked(ps)) {
            installFlags |= PackageManager.INSTALL_FORWARD_LOCK;
        }
        return installFlags;
    }

    private void deleteTempPackageFiles() {
        final FilenameFilter filter = new FilenameFilter() {
            public boolean accept(File dir, String name) {
                return name.startsWith("vmdl") && name.endsWith(".tmp");
            }
        };
        deleteTempPackageFilesInDirectory(mAppInstallDir, filter);
        deleteTempPackageFilesInDirectory(mDrmAppPrivateInstallDir, filter);
    }

    private static final void deleteTempPackageFilesInDirectory(File directory,
            FilenameFilter filter) {
        final String[] tmpFilesList = directory.list(filter);
        if (tmpFilesList == null) {
            return;
        }
        for (int i = 0; i < tmpFilesList.length; i++) {
            final File tmpFile = new File(directory, tmpFilesList[i]);
            tmpFile.delete();
        }
    }

    private File createTempPackageFile(File installDir) {
        File tmpPackageFile;
        try {
            tmpPackageFile = File.createTempFile("vmdl", ".tmp", installDir);
        } catch (IOException e) {
            Slog.e(TAG, "Couldn't create temp file for downloaded package file.");
            return null;
        }
        try {
            FileUtils.setPermissions(
                    tmpPackageFile.getCanonicalPath(), FileUtils.S_IRUSR|FileUtils.S_IWUSR,
                    -1, -1);
            if (!SELinux.restorecon(tmpPackageFile)) {
                return null;
            }
        } catch (IOException e) {
            Slog.e(TAG, "Trouble getting the canoncical path for a temp file.");
            return null;
        }
        return tmpPackageFile;
    }

    @Override
    public void deletePackageAsUser(final String packageName,
                                    final IPackageDeleteObserver observer,
                                    final int userId, final int flags) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.DELETE_PACKAGES, null);
        final int uid = Binder.getCallingUid();
        if (UserHandle.getUserId(uid) != userId) {
            mContext.enforceCallingPermission(
                    android.Manifest.permission.INTERACT_ACROSS_USERS_FULL,
                    "deletePackage for user " + userId);
        }
        if (isUserRestricted(userId, UserManager.DISALLOW_UNINSTALL_APPS)) {
            try {
                observer.packageDeleted(packageName, PackageManager.DELETE_FAILED_USER_RESTRICTED);
            } catch (RemoteException re) {
            }
            return;
        }

        if (DEBUG_REMOVE) Slog.d(TAG, "deletePackageAsUser: pkg=" + packageName + " user=" + userId);
        // Queue up an async operation since the package deletion may take a little while.
        mHandler.post(new Runnable() {
            public void run() {
                mHandler.removeCallbacks(this);
                final int returnCode = deletePackageX(packageName, userId, flags);
                if (observer != null) {
                    try {
                        observer.packageDeleted(packageName, returnCode);
                    } catch (RemoteException e) {
                        Log.i(TAG, "Observer no longer exists.");
                    } //end catch
                } //end if
            } //end run
        });
    }

    private boolean isPackageDeviceAdmin(String packageName, int userId) {
        IDevicePolicyManager dpm = IDevicePolicyManager.Stub.asInterface(
                ServiceManager.getService(Context.DEVICE_POLICY_SERVICE));
        try {
            if (dpm != null && (dpm.packageHasActiveAdmins(packageName, userId)
                    || dpm.isDeviceOwner(packageName))) {
                return true;
            }
        } catch (RemoteException e) {
        }
        return false;
    }

    /**
     *  This method is an internal method that could be get invoked either
     *  to delete an installed package or to clean up a failed installation.
     *  After deleting an installed package, a broadcast is sent to notify any
     *  listeners that the package has been installed. For cleaning up a failed
     *  installation, the broadcast is not necessary since the package's
     *  installation wouldn't have sent the initial broadcast either
     *  The key steps in deleting a package are
     *  deleting the package information in internal structures like mPackages,
     *  deleting the packages base directories through installd
     *  updating mSettings to reflect current status
     *  persisting settings for later use
     *  sending a broadcast if necessary
     */
    private int deletePackageX(String packageName, int userId, int flags) {
        final PackageRemovedInfo info = new PackageRemovedInfo();
        final boolean res;

        if (isPackageDeviceAdmin(packageName, userId)) {
            Slog.w(TAG, "Not removing package " + packageName + ": has active device admin");
            return PackageManager.DELETE_FAILED_DEVICE_POLICY_MANAGER;
        }

        boolean removedForAllUsers = false;
        boolean systemUpdate = false;

        // for the uninstall-updates case and restricted profiles, remember the per-
        // userhandle installed state
        int[] allUsers;
        boolean[] perUserInstalled;
        synchronized (mPackages) {
            PackageSetting ps = mSettings.mPackages.get(packageName);
            allUsers = sUserManager.getUserIds();
            perUserInstalled = new boolean[allUsers.length];
            for (int i = 0; i < allUsers.length; i++) {
                perUserInstalled[i] = ps != null ? ps.getInstalled(allUsers[i]) : false;
            }
        }

        synchronized (mInstallLock) {
            if (DEBUG_REMOVE) Slog.d(TAG, "deletePackageX: pkg=" + packageName + " user=" + userId);
            res = deletePackageLI(packageName,
                    (flags & PackageManager.DELETE_ALL_USERS) != 0
                            ? UserHandle.ALL : new UserHandle(userId),
                    true, allUsers, perUserInstalled,
                    flags | REMOVE_CHATTY, info, true);
            systemUpdate = info.isRemovedPackageSystemUpdate;
            if (res && !systemUpdate && mPackages.get(packageName) == null) {
                removedForAllUsers = true;
            }
            if (DEBUG_REMOVE) Slog.d(TAG, "delete res: systemUpdate=" + systemUpdate
                    + " removedForAllUsers=" + removedForAllUsers);
        }

        if (res) {
            info.sendBroadcast(true, systemUpdate, removedForAllUsers);

            // If the removed package was a system update, the old system package
            // was re-enabled; we need to broadcast this information
            if (systemUpdate) {
                Bundle extras = new Bundle(1);
                extras.putInt(Intent.EXTRA_UID, info.removedAppId >= 0
                        ? info.removedAppId : info.uid);
                extras.putBoolean(Intent.EXTRA_REPLACING, true);

                sendPackageBroadcast(Intent.ACTION_PACKAGE_ADDED, packageName,
                        extras, null, null, null);
                sendPackageBroadcast(Intent.ACTION_PACKAGE_REPLACED, packageName,
                        extras, null, null, null);
                sendPackageBroadcast(Intent.ACTION_MY_PACKAGE_REPLACED, null,
                        null, packageName, null, null);
            }
        }
        // Force a gc here.
        Runtime.getRuntime().gc();
        // Delete the resources here after sending the broadcast to let
        // other processes clean up before deleting resources.
        if (info.args != null) {
            synchronized (mInstallLock) {
                info.args.doPostDeleteLI(true);
            }
        }

        return res ? PackageManager.DELETE_SUCCEEDED : PackageManager.DELETE_FAILED_INTERNAL_ERROR;
    }

    static class PackageRemovedInfo {
        String removedPackage;
        int uid = -1;
        int removedAppId = -1;
        int[] removedUsers = null;
        boolean isRemovedPackageSystemUpdate = false;
        // Clean up resources deleted packages.
        InstallArgs args = null;

        void sendBroadcast(boolean fullRemove, boolean replacing, boolean removedForAllUsers) {
            Bundle extras = new Bundle(1);
            extras.putInt(Intent.EXTRA_UID, removedAppId >= 0 ? removedAppId : uid);
            extras.putBoolean(Intent.EXTRA_DATA_REMOVED, fullRemove);
            if (replacing) {
                extras.putBoolean(Intent.EXTRA_REPLACING, true);
            }
            extras.putBoolean(Intent.EXTRA_REMOVED_FOR_ALL_USERS, removedForAllUsers);
            if (removedPackage != null) {
                sendPackageBroadcast(Intent.ACTION_PACKAGE_REMOVED, removedPackage,
                        extras, null, null, removedUsers);
                if (fullRemove && !replacing) {
                    sendPackageBroadcast(Intent.ACTION_PACKAGE_FULLY_REMOVED, removedPackage,
                            extras, null, null, removedUsers);
                }
            }
            if (removedAppId >= 0) {
                sendPackageBroadcast(Intent.ACTION_UID_REMOVED, null, extras, null, null,
                        removedUsers);
            }
        }
    }

    /*
     * This method deletes the package from internal data structures. If the DONT_DELETE_DATA
     * flag is not set, the data directory is removed as well.
     * make sure this flag is set for partially installed apps. If not its meaningless to
     * delete a partially installed application.
     */
    private void removePackageDataLI(PackageSetting ps,
            int[] allUserHandles, boolean[] perUserInstalled,
            PackageRemovedInfo outInfo, int flags, boolean writeSettings) {
        String packageName = ps.name;
        if (DEBUG_REMOVE) Slog.d(TAG, "removePackageDataLI: " + ps);
        removePackageLI(ps, (flags&REMOVE_CHATTY) != 0);
        // Retrieve object to delete permissions for shared user later on
        final PackageSetting deletedPs;
        // reader
        synchronized (mPackages) {
            deletedPs = mSettings.mPackages.get(packageName);
            if (outInfo != null) {
                outInfo.removedPackage = packageName;
                outInfo.removedUsers = deletedPs != null
                        ? deletedPs.queryInstalledUsers(sUserManager.getUserIds(), true)
                        : null;
            }
        }
        if ((flags&PackageManager.DELETE_KEEP_DATA) == 0) {
            removeDataDirsLI(packageName);
            schedulePackageCleaning(packageName, UserHandle.USER_ALL, true);
        }
        // writer
        synchronized (mPackages) {
            if (deletedPs != null) {
                if ((flags&PackageManager.DELETE_KEEP_DATA) == 0) {
                    if (outInfo != null) {
                        outInfo.removedAppId = mSettings.removePackageLPw(packageName);
                    }
                    if (deletedPs != null) {
                        updatePermissionsLPw(deletedPs.name, null, 0);
                        if (deletedPs.sharedUser != null) {
                            // remove permissions associated with package
                            mSettings.updateSharedUserPermsLPw(deletedPs, mGlobalGids);
                        }
                    }
                    clearPackagePreferredActivitiesLPw(deletedPs.name, UserHandle.USER_ALL);
                }
                // make sure to preserve per-user disabled state if this removal was just
                // a downgrade of a system app to the factory package
                if (allUserHandles != null && perUserInstalled != null) {
                    if (DEBUG_REMOVE) {
                        Slog.d(TAG, "Propagating install state across downgrade");
                    }
                    for (int i = 0; i < allUserHandles.length; i++) {
                        if (DEBUG_REMOVE) {
                            Slog.d(TAG, "    user " + allUserHandles[i]
                                    + " => " + perUserInstalled[i]);
                        }
                        ps.setInstalled(perUserInstalled[i], allUserHandles[i]);
                    }
                }
            }
            // can downgrade to reader
            if (writeSettings) {
                // Save settings now
                mSettings.writeLPr();
            }
        }
        if (outInfo != null) {
            // A user ID was deleted here. Go through all users and remove it
            // from KeyStore.
            removeKeystoreDataIfNeeded(UserHandle.USER_ALL, outInfo.removedAppId);
        }
    }

    static boolean locationIsPrivileged(File path) {
        try {
            final String privilegedAppDir = new File(Environment.getRootDirectory(), "priv-app")
                    .getCanonicalPath();
            return path.getCanonicalPath().startsWith(privilegedAppDir);
        } catch (IOException e) {
            Slog.e(TAG, "Unable to access code path " + path);
        }
        return false;
    }

    /*
     * Tries to delete system package.
     */
    private boolean deleteSystemPackageLI(PackageSetting newPs,
            int[] allUserHandles, boolean[] perUserInstalled,
            int flags, PackageRemovedInfo outInfo, boolean writeSettings) {
        final boolean applyUserRestrictions
                = (allUserHandles != null) && (perUserInstalled != null);
        PackageSetting disabledPs = null;
        // Confirm if the system package has been updated
        // An updated system app can be deleted. This will also have to restore
        // the system pkg from system partition
        // reader
        synchronized (mPackages) {
            disabledPs = mSettings.getDisabledSystemPkgLPr(newPs.name);
        }
        if (DEBUG_REMOVE) Slog.d(TAG, "deleteSystemPackageLI: newPs=" + newPs
                + " disabledPs=" + disabledPs);
        if (disabledPs == null) {
            Slog.w(TAG, "Attempt to delete unknown system package "+ newPs.name);
            return false;
        } else if (DEBUG_REMOVE) {
            Slog.d(TAG, "Deleting system pkg from data partition");
        }
        if (DEBUG_REMOVE) {
            if (applyUserRestrictions) {
                Slog.d(TAG, "Remembering install states:");
                for (int i = 0; i < allUserHandles.length; i++) {
                    Slog.d(TAG, "   u=" + allUserHandles[i] + " inst=" + perUserInstalled[i]);
                }
            }
        }
        // Delete the updated package
        outInfo.isRemovedPackageSystemUpdate = true;
        if (disabledPs.versionCode < newPs.versionCode) {
            // Delete data for downgrades
            flags &= ~PackageManager.DELETE_KEEP_DATA;
        } else {
            // Preserve data by setting flag
            flags |= PackageManager.DELETE_KEEP_DATA;
        }
        boolean ret = deleteInstalledPackageLI(newPs, true, flags,
                allUserHandles, perUserInstalled, outInfo, writeSettings);
        if (!ret) {
            return false;
        }
        // writer
        synchronized (mPackages) {
            // Reinstate the old system package
            mSettings.enableSystemPackageLPw(newPs.name);
            // Remove any native libraries from the upgraded package.
            NativeLibraryHelper.removeNativeBinariesLI(newPs.nativeLibraryPathString);
        }
        // Install the system package
        if (DEBUG_REMOVE) Slog.d(TAG, "Re-installing system package: " + disabledPs);
        int parseFlags = PackageParser.PARSE_MUST_BE_APK | PackageParser.PARSE_IS_SYSTEM;
        if (locationIsPrivileged(disabledPs.codePath)) {
            parseFlags |= PackageParser.PARSE_IS_PRIVILEGED;
        }
        PackageParser.Package newPkg = scanPackageLI(disabledPs.codePath,
                parseFlags, SCAN_MONITOR | SCAN_NO_PATHS, 0, null, null);

        if (newPkg == null) {
            Slog.w(TAG, "Failed to restore system package:" + newPs.name
                    + " with error:" + mLastScanError);
            return false;
        }
        // writer
        synchronized (mPackages) {
            PackageSetting ps = mSettings.mPackages.get(newPkg.packageName);
            setInternalAppNativeLibraryPath(newPkg, ps);
            updatePermissionsLPw(newPkg.packageName, newPkg,
                    UPDATE_PERMISSIONS_ALL | UPDATE_PERMISSIONS_REPLACE_PKG);
            if (applyUserRestrictions) {
                if (DEBUG_REMOVE) {
                    Slog.d(TAG, "Propagating install state across reinstall");
                }
                for (int i = 0; i < allUserHandles.length; i++) {
                    if (DEBUG_REMOVE) {
                        Slog.d(TAG, "    user " + allUserHandles[i]
                                + " => " + perUserInstalled[i]);
                    }
                    ps.setInstalled(perUserInstalled[i], allUserHandles[i]);
                }
                // Regardless of writeSettings we need to ensure that this restriction
                // state propagation is persisted
                mSettings.writeAllUsersPackageRestrictionsLPr();
            }
            // can downgrade to reader here
            if (writeSettings) {
                mSettings.writeLPr();
            }
        }
        return true;
    }

    private boolean deleteInstalledPackageLI(PackageSetting ps,
            boolean deleteCodeAndResources, int flags,
            int[] allUserHandles, boolean[] perUserInstalled,
            PackageRemovedInfo outInfo, boolean writeSettings) {
        if (outInfo != null) {
            outInfo.uid = ps.appId;
        }

        // Delete package data from internal structures and also remove data if flag is set
        removePackageDataLI(ps, allUserHandles, perUserInstalled, outInfo, flags, writeSettings);

        // Delete application code and resources
        if (deleteCodeAndResources && (outInfo != null)) {
            outInfo.args = createInstallArgs(packageFlagsToInstallFlags(ps), ps.codePathString,
                    ps.resourcePathString, ps.nativeLibraryPathString,
                    getAppInstructionSetFromSettings(ps));
        }
        return true;
    }

    /*
     * This method handles package deletion in general
     */
    private boolean deletePackageLI(String packageName, UserHandle user,
            boolean deleteCodeAndResources, int[] allUserHandles, boolean[] perUserInstalled,
            int flags, PackageRemovedInfo outInfo,
            boolean writeSettings) {
        if (packageName == null) {
            Slog.w(TAG, "Attempt to delete null packageName.");
            return false;
        }
        if (DEBUG_REMOVE) Slog.d(TAG, "deletePackageLI: " + packageName + " user " + user);
        PackageSetting ps;
        boolean dataOnly = false;
        int removeUser = -1;
        int appId = -1;
        synchronized (mPackages) {
            ps = mSettings.mPackages.get(packageName);
            if (ps == null) {
                Slog.w(TAG, "Package named '" + packageName + "' doesn't exist.");
                return false;
            }
            if ((!isSystemApp(ps) || (flags&PackageManager.DELETE_SYSTEM_APP) != 0) && user != null
                    && user.getIdentifier() != UserHandle.USER_ALL) {
                // The caller is asking that the package only be deleted for a single
                // user.  To do this, we just mark its uninstalled state and delete
                // its data.  If this is a system app, we only allow this to happen if
                // they have set the special DELETE_SYSTEM_APP which requests different
                // semantics than normal for uninstalling system apps.
                if (DEBUG_REMOVE) Slog.d(TAG, "Only deleting for single user");
                ps.setUserState(user.getIdentifier(),
                        COMPONENT_ENABLED_STATE_DEFAULT,
                        false, //installed
                        true,  //stopped
                        true,  //notLaunched
                        false, //blocked
                        null, null, null);
                if (!isSystemApp(ps)) {
                    if (ps.isAnyInstalled(sUserManager.getUserIds())) {
                        // Other user still have this package installed, so all
                        // we need to do is clear this user's data and save that
                        // it is uninstalled.
                        if (DEBUG_REMOVE) Slog.d(TAG, "Still installed by other users");
                        removeUser = user.getIdentifier();
                        appId = ps.appId;
                        mSettings.writePackageRestrictionsLPr(removeUser);
                    } else {
                        // We need to set it back to 'installed' so the uninstall
                        // broadcasts will be sent correctly.
                        if (DEBUG_REMOVE) Slog.d(TAG, "Not installed by other users, full delete");
                        ps.setInstalled(true, user.getIdentifier());
                    }
                } else {
                    // This is a system app, so we assume that the
                    // other users still have this package installed, so all
                    // we need to do is clear this user's data and save that
                    // it is uninstalled.
                    if (DEBUG_REMOVE) Slog.d(TAG, "Deleting system app");
                    removeUser = user.getIdentifier();
                    appId = ps.appId;
                    mSettings.writePackageRestrictionsLPr(removeUser);
                }
            }
        }

        if (removeUser >= 0) {
            // From above, we determined that we are deleting this only
            // for a single user.  Continue the work here.
            if (DEBUG_REMOVE) Slog.d(TAG, "Updating install state for user: " + removeUser);
            if (outInfo != null) {
                outInfo.removedPackage = packageName;
                outInfo.removedAppId = appId;
                outInfo.removedUsers = new int[] {removeUser};
            }
            mInstaller.clearUserData(packageName, removeUser);
            removeKeystoreDataIfNeeded(removeUser, appId);
            schedulePackageCleaning(packageName, removeUser, false);
            return true;
        }

        if (dataOnly) {
            // Delete application data first
            if (DEBUG_REMOVE) Slog.d(TAG, "Removing package data only");
            removePackageDataLI(ps, null, null, outInfo, flags, writeSettings);
            return true;
        }

        boolean ret = false;
        mSettings.mKeySetManager.removeAppKeySetData(packageName);
        if (isSystemApp(ps)) {
            if (DEBUG_REMOVE) Slog.d(TAG, "Removing system package:" + ps.name);
            // When an updated system application is deleted we delete the existing resources as well and
            // fall back to existing code in system partition
            ret = deleteSystemPackageLI(ps, allUserHandles, perUserInstalled,
                    flags, outInfo, writeSettings);
        } else {
            if (DEBUG_REMOVE) Slog.d(TAG, "Removing non-system package:" + ps.name);
            // Kill application pre-emptively especially for apps on sd.
            killApplication(packageName, ps.appId, "uninstall pkg");
            ret = deleteInstalledPackageLI(ps, deleteCodeAndResources, flags,
                    allUserHandles, perUserInstalled,
                    outInfo, writeSettings);
        }

        return ret;
    }

    private final class ClearStorageConnection implements ServiceConnection {
        IMediaContainerService mContainerService;

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (this) {
                mContainerService = IMediaContainerService.Stub.asInterface(service);
                notifyAll();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
        }
    }

    private void clearExternalStorageDataSync(String packageName, int userId, boolean allData) {
        final boolean mounted;
        if (Environment.isExternalStorageEmulated()) {
            mounted = true;
        } else {
            final String status = Environment.getExternalStorageState();

            mounted = status.equals(Environment.MEDIA_MOUNTED)
                    || status.equals(Environment.MEDIA_MOUNTED_READ_ONLY);
        }

        if (!mounted) {
            return;
        }

        final Intent containerIntent = new Intent().setComponent(DEFAULT_CONTAINER_COMPONENT);
        int[] users;
        if (userId == UserHandle.USER_ALL) {
            users = sUserManager.getUserIds();
        } else {
            users = new int[] { userId };
        }
        final ClearStorageConnection conn = new ClearStorageConnection();
        if (mContext.bindServiceAsUser(
                containerIntent, conn, Context.BIND_AUTO_CREATE, UserHandle.OWNER)) {
            try {
                for (int curUser : users) {
                    long timeout = SystemClock.uptimeMillis() + 5000;
                    synchronized (conn) {
                        long now = SystemClock.uptimeMillis();
                        while (conn.mContainerService == null && now < timeout) {
                            try {
                                conn.wait(timeout - now);
                            } catch (InterruptedException e) {
                            }
                        }
                    }
                    if (conn.mContainerService == null) {
                        return;
                    }

                    final UserEnvironment userEnv = new UserEnvironment(curUser);
                    clearDirectory(conn.mContainerService,
                            userEnv.buildExternalStorageAppCacheDirs(packageName));
                    if (allData) {
                        clearDirectory(conn.mContainerService,
                                userEnv.buildExternalStorageAppDataDirs(packageName));
                        clearDirectory(conn.mContainerService,
                                userEnv.buildExternalStorageAppMediaDirs(packageName));
                    }
                }
            } finally {
                mContext.unbindService(conn);
            }
        }
    }

    @Override
    public void clearApplicationUserData(final String packageName,
            final IPackageDataObserver observer, final int userId) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CLEAR_APP_USER_DATA, null);
        enforceCrossUserPermission(Binder.getCallingUid(), userId, true, "clear application data");
        // Queue up an async operation since the package deletion may take a little while.
        mHandler.post(new Runnable() {
            public void run() {
                mHandler.removeCallbacks(this);
                final boolean succeeded;
                synchronized (mInstallLock) {
                    succeeded = clearApplicationUserDataLI(packageName, userId);
                }
                clearExternalStorageDataSync(packageName, userId, true);
                if (succeeded) {
                    // invoke DeviceStorageMonitor's update method to clear any notifications
                    DeviceStorageMonitorService dsm = (DeviceStorageMonitorService)
                            ServiceManager.getService(DeviceStorageMonitorService.SERVICE);
                    if (dsm != null) {
                        dsm.updateMemory();
                    }
                }
                if(observer != null) {
                    try {
                        observer.onRemoveCompleted(packageName, succeeded);
                    } catch (RemoteException e) {
                        Log.i(TAG, "Observer no longer exists.");
                    }
                } //end if observer
            } //end run
        });
    }

    private boolean clearApplicationUserDataLI(String packageName, int userId) {
        if (packageName == null) {
            Slog.w(TAG, "Attempt to delete null packageName.");
            return false;
        }
        PackageParser.Package p;
        boolean dataOnly = false;
        final int appId;
        synchronized (mPackages) {
            p = mPackages.get(packageName);
            if (p == null) {
                dataOnly = true;
                PackageSetting ps = mSettings.mPackages.get(packageName);
                if ((ps == null) || (ps.pkg == null)) {
                    Slog.w(TAG, "Package named '" + packageName + "' doesn't exist.");
                    return false;
                }
                p = ps.pkg;
            }
            if (!dataOnly) {
                // need to check this only for fully installed applications
                if (p == null) {
                    Slog.w(TAG, "Package named '" + packageName + "' doesn't exist.");
                    return false;
                }
                final ApplicationInfo applicationInfo = p.applicationInfo;
                if (applicationInfo == null) {
                    Slog.w(TAG, "Package " + packageName + " has no applicationInfo.");
                    return false;
                }
            }
            if (p != null && p.applicationInfo != null) {
                appId = p.applicationInfo.uid;
            } else {
                appId = -1;
            }
        }
        int retCode = mInstaller.clearUserData(packageName, userId);
        if (retCode < 0) {
            Slog.w(TAG, "Couldn't remove cache files for package: "
                    + packageName);
            return false;
        }
        removeKeystoreDataIfNeeded(userId, appId);
        return true;
    }

    /**
     * Remove entries from the keystore daemon. Will only remove it if the
     * {@code appId} is valid.
     */
    private static void removeKeystoreDataIfNeeded(int userId, int appId) {
        if (appId < 0) {
            return;
        }

        final KeyStore keyStore = KeyStore.getInstance();
        if (keyStore != null) {
            if (userId == UserHandle.USER_ALL) {
                for (final int individual : sUserManager.getUserIds()) {
                    keyStore.clearUid(UserHandle.getUid(individual, appId));
                }
            } else {
                keyStore.clearUid(UserHandle.getUid(userId, appId));
            }
        } else {
            Slog.w(TAG, "Could not contact keystore to clear entries for app id " + appId);
        }
    }

    @Override
    public void deleteApplicationCacheFiles(final String packageName,
            final IPackageDataObserver observer) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.DELETE_CACHE_FILES, null);
        // Queue up an async operation since the package deletion may take a little while.
        final int userId = UserHandle.getCallingUserId();
        mHandler.post(new Runnable() {
            public void run() {
                mHandler.removeCallbacks(this);
                final boolean succeded;
                synchronized (mInstallLock) {
                    succeded = deleteApplicationCacheFilesLI(packageName, userId);
                }
                clearExternalStorageDataSync(packageName, userId, false);
                if(observer != null) {
                    try {
                        observer.onRemoveCompleted(packageName, succeded);
                    } catch (RemoteException e) {
                        Log.i(TAG, "Observer no longer exists.");
                    }
                } //end if observer
            } //end run
        });
    }

    private boolean deleteApplicationCacheFilesLI(String packageName, int userId) {
        if (packageName == null) {
            Slog.w(TAG, "Attempt to delete null packageName.");
            return false;
        }
        PackageParser.Package p;
        synchronized (mPackages) {
            p = mPackages.get(packageName);
        }
        if (p == null) {
            Slog.w(TAG, "Package named '" + packageName +"' doesn't exist.");
            return false;
        }
        final ApplicationInfo applicationInfo = p.applicationInfo;
        if (applicationInfo == null) {
            Slog.w(TAG, "Package " + packageName + " has no applicationInfo.");
            return false;
        }
        int retCode = mInstaller.deleteCacheFiles(packageName, userId);
        if (retCode < 0) {
            Slog.w(TAG, "Couldn't remove cache files for package: "
                       + packageName + " u" + userId);
            return false;
        }
        return true;
    }

    @Override
    public void getPackageSizeInfo(final String packageName, int userHandle,
            final IPackageStatsObserver observer) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.GET_PACKAGE_SIZE, null);

        PackageStats stats = new PackageStats(packageName, userHandle);

        /*
         * Queue up an async operation since the package measurement may take a
         * little while.
         */
        Message msg = mHandler.obtainMessage(INIT_COPY);
        msg.obj = new MeasureParams(stats, observer);
        mHandler.sendMessage(msg);
    }

    private boolean getPackageSizeInfoLI(String packageName, int userHandle,
            PackageStats pStats) {
        if (packageName == null) {
            Slog.w(TAG, "Attempt to get size of null packageName.");
            return false;
        }
        PackageParser.Package p;
        boolean dataOnly = false;
        String libDirPath = null;
        String asecPath = null;
        PackageSetting ps = null;
        synchronized (mPackages) {
            p = mPackages.get(packageName);
            ps = mSettings.mPackages.get(packageName);
            if(p == null) {
                dataOnly = true;
                if((ps == null) || (ps.pkg == null)) {
                    Slog.w(TAG, "Package named '" + packageName +"' doesn't exist.");
                    return false;
                }
                p = ps.pkg;
            }
            if (ps != null) {
                libDirPath = ps.nativeLibraryPathString;
            }
            if (p != null && (isExternal(p) || isForwardLocked(p))) {
                String secureContainerId = cidFromCodePath(p.applicationInfo.sourceDir);
                if (secureContainerId != null) {
                    asecPath = PackageHelper.getSdFilesystem(secureContainerId);
                }
            }
        }
        String publicSrcDir = null;
        if(!dataOnly) {
            final ApplicationInfo applicationInfo = p.applicationInfo;
            if (applicationInfo == null) {
                Slog.w(TAG, "Package " + packageName + " has no applicationInfo.");
                return false;
            }
            if (isForwardLocked(p)) {
                publicSrcDir = applicationInfo.publicSourceDir;
            }
        }

        String dexCodeInstructionSet = getDexCodeInstructionSet(getAppInstructionSetFromSettings(ps));
        int res = mInstaller.getSizeInfo(packageName, userHandle, p.mPath, libDirPath,
                publicSrcDir, asecPath, dexCodeInstructionSet, pStats);
        if (res < 0) {
            return false;
        }

        // Fix-up for forward-locked applications in ASEC containers.
        if (!isExternal(p)) {
            pStats.codeSize += pStats.externalCodeSize;
            pStats.externalCodeSize = 0L;
        }

        return true;
    }


    @Override
    public void addPackageToPreferred(String packageName) {
        Slog.w(TAG, "addPackageToPreferred: this is now a no-op");
    }

    @Override
    public void removePackageFromPreferred(String packageName) {
        Slog.w(TAG, "removePackageFromPreferred: this is now a no-op");
    }

    @Override
    public List<PackageInfo> getPreferredPackages(int flags) {
        return new ArrayList<PackageInfo>();
    }

    private int getUidTargetSdkVersionLockedLPr(int uid) {
        Object obj = mSettings.getUserIdLPr(uid);
        if (obj instanceof SharedUserSetting) {
            final SharedUserSetting sus = (SharedUserSetting) obj;
            int vers = Build.VERSION_CODES.CUR_DEVELOPMENT;
            final Iterator<PackageSetting> it = sus.packages.iterator();
            while (it.hasNext()) {
                final PackageSetting ps = it.next();
                if (ps.pkg != null) {
                    int v = ps.pkg.applicationInfo.targetSdkVersion;
                    if (v < vers) vers = v;
                }
            }
            return vers;
        } else if (obj instanceof PackageSetting) {
            final PackageSetting ps = (PackageSetting) obj;
            if (ps.pkg != null) {
                return ps.pkg.applicationInfo.targetSdkVersion;
            }
        }
        return Build.VERSION_CODES.CUR_DEVELOPMENT;
    }

    @Override
    public void addPreferredActivity(IntentFilter filter, int match,
            ComponentName[] set, ComponentName activity, int userId) {
        addPreferredActivityInternal(filter, match, set, activity, true, userId);
    }

    private void addPreferredActivityInternal(IntentFilter filter, int match,
            ComponentName[] set, ComponentName activity, boolean always, int userId) {
        // writer
        int callingUid = Binder.getCallingUid();
        enforceCrossUserPermission(callingUid, userId, true, "add preferred activity");
        if (filter.countActions() == 0) {
            Slog.w(TAG, "Cannot set a preferred activity with no filter actions");
            return;
        }
        synchronized (mPackages) {
            if (mContext.checkCallingOrSelfPermission(
                    android.Manifest.permission.SET_PREFERRED_APPLICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                if (getUidTargetSdkVersionLockedLPr(callingUid)
                        < Build.VERSION_CODES.FROYO) {
                    Slog.w(TAG, "Ignoring addPreferredActivity() from uid "
                            + callingUid);
                    return;
                }
                mContext.enforceCallingOrSelfPermission(
                        android.Manifest.permission.SET_PREFERRED_APPLICATIONS, null);
            }

            Slog.i(TAG, "Adding preferred activity " + activity + " for user " + userId + " :");
            filter.dump(new LogPrinter(Log.INFO, TAG), "  ");
            mSettings.editPreferredActivitiesLPw(userId).addFilter(
                    new PreferredActivity(filter, match, set, activity, always));
            mSettings.writePackageRestrictionsLPr(userId);
        }
    }

    @Override
    public void replacePreferredActivity(IntentFilter filter, int match,
            ComponentName[] set, ComponentName activity) {
        if (filter.countActions() != 1) {
            throw new IllegalArgumentException(
                    "replacePreferredActivity expects filter to have only 1 action.");
        }
        if (filter.countDataAuthorities() != 0
                || filter.countDataPaths() != 0
                || filter.countDataSchemes() > 1
                || filter.countDataTypes() != 0) {
            throw new IllegalArgumentException(
                    "replacePreferredActivity expects filter to have no data authorities, " +
                    "paths, or types; and at most one scheme.");
        }
        synchronized (mPackages) {
            if (mContext.checkCallingOrSelfPermission(
                    android.Manifest.permission.SET_PREFERRED_APPLICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                if (getUidTargetSdkVersionLockedLPr(Binder.getCallingUid())
                        < Build.VERSION_CODES.FROYO) {
                    Slog.w(TAG, "Ignoring replacePreferredActivity() from uid "
                            + Binder.getCallingUid());
                    return;
                }
                mContext.enforceCallingOrSelfPermission(
                        android.Manifest.permission.SET_PREFERRED_APPLICATIONS, null);
            }

            final int callingUserId = UserHandle.getCallingUserId();
            PreferredIntentResolver pir = mSettings.mPreferredActivities.get(callingUserId);
            if (pir != null) {
                Intent intent = new Intent(filter.getAction(0)).addCategory(filter.getCategory(0));
                if (filter.countDataSchemes() == 1) {
                    Uri.Builder builder = new Uri.Builder();
                    builder.scheme(filter.getDataScheme(0));
                    intent.setData(builder.build());
                }
                List<PreferredActivity> matches = pir.queryIntent(
                        intent, null, true, callingUserId);
                if (DEBUG_PREFERRED) {
                    Slog.i(TAG, matches.size() + " preferred matches for " + intent);
                }
                for (int i = 0; i < matches.size(); i++) {
                    PreferredActivity pa = matches.get(i);
                    if (DEBUG_PREFERRED) {
                        Slog.i(TAG, "Removing preferred activity "
                                + pa.mPref.mComponent + ":");
                        filter.dump(new LogPrinter(Log.INFO, TAG), "  ");
                    }
                    pir.removeFilter(pa);
                }
            }
            addPreferredActivityInternal(filter, match, set, activity, true, callingUserId);
        }
    }

    @Override
    public void clearPackagePreferredActivities(String packageName) {
        final int uid = Binder.getCallingUid();
        // writer
        synchronized (mPackages) {
            PackageParser.Package pkg = mPackages.get(packageName);
            if (pkg == null || pkg.applicationInfo.uid != uid) {
                if (mContext.checkCallingOrSelfPermission(
                        android.Manifest.permission.SET_PREFERRED_APPLICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                    if (getUidTargetSdkVersionLockedLPr(Binder.getCallingUid())
                            < Build.VERSION_CODES.FROYO) {
                        Slog.w(TAG, "Ignoring clearPackagePreferredActivities() from uid "
                                + Binder.getCallingUid());
                        return;
                    }
                    mContext.enforceCallingOrSelfPermission(
                            android.Manifest.permission.SET_PREFERRED_APPLICATIONS, null);
                }
            }

            int user = UserHandle.getCallingUserId();
            if (clearPackagePreferredActivitiesLPw(packageName, user)) {
                mSettings.writePackageRestrictionsLPr(user);
                scheduleWriteSettingsLocked();
            }
        }
    }

    /** This method takes a specific user id as well as UserHandle.USER_ALL. */
    boolean clearPackagePreferredActivitiesLPw(String packageName, int userId) {
        ArrayList<PreferredActivity> removed = null;
        boolean changed = false;
        for (int i=0; i<mSettings.mPreferredActivities.size(); i++) {
            final int thisUserId = mSettings.mPreferredActivities.keyAt(i);
            PreferredIntentResolver pir = mSettings.mPreferredActivities.valueAt(i);
            if (userId != UserHandle.USER_ALL && userId != thisUserId) {
                continue;
            }
            Iterator<PreferredActivity> it = pir.filterIterator();
            while (it.hasNext()) {
                PreferredActivity pa = it.next();
                // Mark entry for removal only if it matches the package name
                // and the entry is of type "always".
                if (packageName == null ||
                        (pa.mPref.mComponent.getPackageName().equals(packageName)
                                && pa.mPref.mAlways)) {
                    if (removed == null) {
                        removed = new ArrayList<PreferredActivity>();
                    }
                    removed.add(pa);
                }
            }
            if (removed != null) {
                for (int j=0; j<removed.size(); j++) {
                    PreferredActivity pa = removed.get(j);
                    pir.removeFilter(pa);
                }
                changed = true;
            }
        }
        return changed;
    }

    @Override
    public void resetPreferredActivities(int userId) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.SET_PREFERRED_APPLICATIONS, null);
        // writer
        synchronized (mPackages) {
            int user = UserHandle.getCallingUserId();
            clearPackagePreferredActivitiesLPw(null, user);
            mSettings.readDefaultPreferredAppsLPw(this, user);
            mSettings.writePackageRestrictionsLPr(user);
            scheduleWriteSettingsLocked();
        }
    }

    @Override
    public int getPreferredActivities(List<IntentFilter> outFilters,
            List<ComponentName> outActivities, String packageName) {

        int num = 0;
        final int userId = UserHandle.getCallingUserId();
        // reader
        synchronized (mPackages) {
            PreferredIntentResolver pir = mSettings.mPreferredActivities.get(userId);
            if (pir != null) {
                final Iterator<PreferredActivity> it = pir.filterIterator();
                while (it.hasNext()) {
                    final PreferredActivity pa = it.next();
                    if (packageName == null
                            || (pa.mPref.mComponent.getPackageName().equals(packageName)
                                    && pa.mPref.mAlways)) {
                        if (outFilters != null) {
                            outFilters.add(new IntentFilter(pa));
                        }
                        if (outActivities != null) {
                            outActivities.add(pa.mPref.mComponent);
                        }
                    }
                }
            }
        }

        return num;
    }

    @Override
    public ComponentName getHomeActivities(List<ResolveInfo> allHomeCandidates) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_HOME);

        final int callingUserId = UserHandle.getCallingUserId();
        List<ResolveInfo> list = queryIntentActivities(intent, null,
                PackageManager.GET_META_DATA, callingUserId);
        ResolveInfo preferred = findPreferredActivity(intent, null, 0, list, 0,
                true, false, false, callingUserId);

        allHomeCandidates.clear();
        if (list != null) {
            for (ResolveInfo ri : list) {
                allHomeCandidates.add(ri);
            }
        }
        return (preferred == null || preferred.activityInfo == null)
                ? null
                : new ComponentName(preferred.activityInfo.packageName,
                        preferred.activityInfo.name);
    }

    @Override
    public void setApplicationEnabledSetting(String appPackageName,
            int newState, int flags, int userId, String callingPackage) {
        if (!sUserManager.exists(userId)) return;
        if (callingPackage == null) {
            callingPackage = Integer.toString(Binder.getCallingUid());
        }
        setEnabledSetting(appPackageName, null, newState, flags, userId, callingPackage);
    }

    @Override
    public void setComponentEnabledSetting(ComponentName componentName,
            int newState, int flags, int userId) {
        if (!sUserManager.exists(userId)) return;
        setEnabledSetting(componentName.getPackageName(),
                componentName.getClassName(), newState, flags, userId, null);
    }

    private void setEnabledSetting(final String packageName, String className, int newState,
            final int flags, int userId, String callingPackage) {
        if (!(newState == COMPONENT_ENABLED_STATE_DEFAULT
              || newState == COMPONENT_ENABLED_STATE_ENABLED
              || newState == COMPONENT_ENABLED_STATE_DISABLED
              || newState == COMPONENT_ENABLED_STATE_DISABLED_USER
              || newState == COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED)) {
            throw new IllegalArgumentException("Invalid new component state: "
                    + newState);
        }
        PackageSetting pkgSetting;
        final int uid = Binder.getCallingUid();
        final int permission = mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_COMPONENT_ENABLED_STATE);
        enforceCrossUserPermission(uid, userId, false, "set enabled");
        final boolean allowedByPermission = (permission == PackageManager.PERMISSION_GRANTED);
        boolean sendNow = false;
        boolean isApp = (className == null);
        String componentName = isApp ? packageName : className;
        int packageUid = -1;
        ArrayList<String> components;

        // writer
        synchronized (mPackages) {
            pkgSetting = mSettings.mPackages.get(packageName);
            if (pkgSetting == null) {
                if (className == null) {
                    throw new IllegalArgumentException(
                            "Unknown package: " + packageName);
                }
                throw new IllegalArgumentException(
                        "Unknown component: " + packageName
                        + "/" + className);
            }
            // Allow root and verify that userId is not being specified by a different user
            if (!allowedByPermission && !UserHandle.isSameApp(uid, pkgSetting.appId)) {
                throw new SecurityException(
                        "Permission Denial: attempt to change component state from pid="
                        + Binder.getCallingPid()
                        + ", uid=" + uid + ", package uid=" + pkgSetting.appId);
            }
            if (className == null) {
                // We're dealing with an application/package level state change
                if (pkgSetting.getEnabled(userId) == newState) {
                    // Nothing to do
                    return;
                }
                if (newState == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                    || newState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                    // Don't care about who enables an app.
                    callingPackage = null;
                }
                pkgSetting.setEnabled(newState, userId, callingPackage);
                // pkgSetting.pkg.mSetEnabled = newState;
            } else {
                // We're dealing with a component level state change
                // First, verify that this is a valid class name.
                PackageParser.Package pkg = pkgSetting.pkg;
                if (pkg == null || !pkg.hasComponentClassName(className)) {
                    if (pkg.applicationInfo.targetSdkVersion >= Build.VERSION_CODES.JELLY_BEAN) {
                        throw new IllegalArgumentException("Component class " + className
                                + " does not exist in " + packageName);
                    } else {
                        Slog.w(TAG, "Failed setComponentEnabledSetting: component class "
                                + className + " does not exist in " + packageName);
                    }
                }
                switch (newState) {
                case COMPONENT_ENABLED_STATE_ENABLED:
                    if (!pkgSetting.enableComponentLPw(className, userId)) {
                        return;
                    }
                    break;
                case COMPONENT_ENABLED_STATE_DISABLED:
                    if (!pkgSetting.disableComponentLPw(className, userId)) {
                        return;
                    }
                    break;
                case COMPONENT_ENABLED_STATE_DEFAULT:
                    if (!pkgSetting.restoreComponentLPw(className, userId)) {
                        return;
                    }
                    break;
                default:
                    Slog.e(TAG, "Invalid new component state: " + newState);
                    return;
                }
            }
            mSettings.writePackageRestrictionsLPr(userId);
            components = mPendingBroadcasts.get(userId, packageName);
            final boolean newPackage = components == null;
            if (newPackage) {
                components = new ArrayList<String>();
            }
            if (!components.contains(componentName)) {
                components.add(componentName);
            }
            if ((flags&PackageManager.DONT_KILL_APP) == 0) {
                sendNow = true;
                // Purge entry from pending broadcast list if another one exists already
                // since we are sending one right away.
                mPendingBroadcasts.remove(userId, packageName);
            } else {
                if (newPackage) {
                    mPendingBroadcasts.put(userId, packageName, components);
                }
                if (!mHandler.hasMessages(SEND_PENDING_BROADCAST)) {
                    // Schedule a message
                    mHandler.sendEmptyMessageDelayed(SEND_PENDING_BROADCAST, BROADCAST_DELAY);
                }
            }
        }

        long callingId = Binder.clearCallingIdentity();
        try {
            if (sendNow) {
                packageUid = UserHandle.getUid(userId, pkgSetting.appId);
                sendPackageChangedBroadcast(packageName,
                        (flags&PackageManager.DONT_KILL_APP) != 0, components, packageUid);
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    private void sendPackageChangedBroadcast(String packageName,
            boolean killFlag, ArrayList<String> componentNames, int packageUid) {
        if (DEBUG_INSTALL)
            Log.v(TAG, "Sending package changed: package=" + packageName + " components="
                    + componentNames);
        Bundle extras = new Bundle(4);
        extras.putString(Intent.EXTRA_CHANGED_COMPONENT_NAME, componentNames.get(0));
        String nameList[] = new String[componentNames.size()];
        componentNames.toArray(nameList);
        extras.putStringArray(Intent.EXTRA_CHANGED_COMPONENT_NAME_LIST, nameList);
        extras.putBoolean(Intent.EXTRA_DONT_KILL_APP, killFlag);
        extras.putInt(Intent.EXTRA_UID, packageUid);
        sendPackageBroadcast(Intent.ACTION_PACKAGE_CHANGED,  packageName, extras, null, null,
                new int[] {UserHandle.getUserId(packageUid)});
    }

    @Override
    public void setPackageStoppedState(String packageName, boolean stopped, int userId) {
        if (!sUserManager.exists(userId)) return;
        final int uid = Binder.getCallingUid();
        final int permission = mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_COMPONENT_ENABLED_STATE);
        final boolean allowedByPermission = (permission == PackageManager.PERMISSION_GRANTED);
        enforceCrossUserPermission(uid, userId, true, "stop package");
        // writer
        synchronized (mPackages) {
            if (mSettings.setPackageStoppedStateLPw(packageName, stopped, allowedByPermission,
                    uid, userId)) {
                scheduleWritePackageRestrictionsLocked(userId);
            }
        }
    }

    @Override
    public String getInstallerPackageName(String packageName) {
        // reader
        synchronized (mPackages) {
            return mSettings.getInstallerPackageNameLPr(packageName);
        }
    }

    @Override
    public int getApplicationEnabledSetting(String packageName, int userId) {
        if (!sUserManager.exists(userId)) return COMPONENT_ENABLED_STATE_DISABLED;
        int uid = Binder.getCallingUid();
        enforceCrossUserPermission(uid, userId, false, "get enabled");
        // reader
        synchronized (mPackages) {
            return mSettings.getApplicationEnabledSettingLPr(packageName, userId);
        }
    }

    @Override
    public int getComponentEnabledSetting(ComponentName componentName, int userId) {
        if (!sUserManager.exists(userId)) return COMPONENT_ENABLED_STATE_DISABLED;
        int uid = Binder.getCallingUid();
        enforceCrossUserPermission(uid, userId, false, "get component enabled");
        // reader
        synchronized (mPackages) {
            return mSettings.getComponentEnabledSettingLPr(componentName, userId);
        }
    }

    @Override
    public void enterSafeMode() {
        enforceSystemOrRoot("Only the system can request entering safe mode");

        if (!mSystemReady) {
            mSafeMode = true;
        }
    }

    @Override
    public void systemReady() {
        mSystemReady = true;

        // Read the compatibilty setting when the system is ready.
        boolean compatibilityModeEnabled = android.provider.Settings.Global.getInt(
                mContext.getContentResolver(),
                android.provider.Settings.Global.COMPATIBILITY_MODE, 1) == 1;
        PackageParser.setCompatibilityModeEnabled(compatibilityModeEnabled);
        if (DEBUG_SETTINGS) {
            Log.d(TAG, "compatibility mode:" + compatibilityModeEnabled);
        }

        synchronized (mPackages) {
            // Verify that all of the preferred activity components actually
            // exist.  It is possible for applications to be updated and at
            // that point remove a previously declared activity component that
            // had been set as a preferred activity.  We try to clean this up
            // the next time we encounter that preferred activity, but it is
            // possible for the user flow to never be able to return to that
            // situation so here we do a sanity check to make sure we haven't
            // left any junk around.
            ArrayList<PreferredActivity> removed = new ArrayList<PreferredActivity>();
            for (int i=0; i<mSettings.mPreferredActivities.size(); i++) {
                PreferredIntentResolver pir = mSettings.mPreferredActivities.valueAt(i);
                removed.clear();
                for (PreferredActivity pa : pir.filterSet()) {
                    if (mActivities.mActivities.get(pa.mPref.mComponent) == null) {
                        removed.add(pa);
                    }
                }
                if (removed.size() > 0) {
                    for (int j=0; j<removed.size(); j++) {
                        PreferredActivity pa = removed.get(i);
                        Slog.w(TAG, "Removing dangling preferred activity: "
                                + pa.mPref.mComponent);
                        pir.removeFilter(pa);
                    }
                    mSettings.writePackageRestrictionsLPr(
                            mSettings.mPreferredActivities.keyAt(i));
                }
            }
        }
        sUserManager.systemReady();
    }

    @Override
    public boolean isSafeMode() {
        return mSafeMode;
    }

    @Override
    public boolean hasSystemUidErrors() {
        return mHasSystemUidErrors;
    }

    static String arrayToString(int[] array) {
        StringBuffer buf = new StringBuffer(128);
        buf.append('[');
        if (array != null) {
            for (int i=0; i<array.length; i++) {
                if (i > 0) buf.append(", ");
                buf.append(array[i]);
            }
        }
        buf.append(']');
        return buf.toString();
    }

    static class DumpState {
        public static final int DUMP_LIBS = 1 << 0;

        public static final int DUMP_FEATURES = 1 << 1;

        public static final int DUMP_RESOLVERS = 1 << 2;

        public static final int DUMP_PERMISSIONS = 1 << 3;

        public static final int DUMP_PACKAGES = 1 << 4;

        public static final int DUMP_SHARED_USERS = 1 << 5;

        public static final int DUMP_MESSAGES = 1 << 6;

        public static final int DUMP_PROVIDERS = 1 << 7;

        public static final int DUMP_VERIFIERS = 1 << 8;

        public static final int DUMP_PREFERRED = 1 << 9;

        public static final int DUMP_PREFERRED_XML = 1 << 10;

        public static final int DUMP_KEYSETS = 1 << 11;

        public static final int OPTION_SHOW_FILTERS = 1 << 0;

        private int mTypes;

        private int mOptions;

        private boolean mTitlePrinted;

        private SharedUserSetting mSharedUser;

        public boolean isDumping(int type) {
            if (mTypes == 0 && type != DUMP_PREFERRED_XML) {
                return true;
            }

            return (mTypes & type) != 0;
        }

        public void setDump(int type) {
            mTypes |= type;
        }

        public boolean isOptionEnabled(int option) {
            return (mOptions & option) != 0;
        }

        public void setOptionEnabled(int option) {
            mOptions |= option;
        }

        public boolean onTitlePrinted() {
            final boolean printed = mTitlePrinted;
            mTitlePrinted = true;
            return printed;
        }

        public boolean getTitlePrinted() {
            return mTitlePrinted;
        }

        public void setTitlePrinted(boolean enabled) {
            mTitlePrinted = enabled;
        }

        public SharedUserSetting getSharedUser() {
            return mSharedUser;
        }

        public void setSharedUser(SharedUserSetting user) {
            mSharedUser = user;
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump ActivityManager from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid()
                    + " without permission "
                    + android.Manifest.permission.DUMP);
            return;
        }

        DumpState dumpState = new DumpState();
        boolean fullPreferred = false;
        boolean checkin = false;

        String packageName = null;
        
        int opti = 0;
        while (opti < args.length) {
            String opt = args[opti];
            if (opt == null || opt.length() <= 0 || opt.charAt(0) != '-') {
                break;
            }
            opti++;
            if ("-a".equals(opt)) {
                // Right now we only know how to print all.
            } else if ("-h".equals(opt)) {
                pw.println("Package manager dump options:");
                pw.println("  [-h] [-f] [--checkin] [cmd] ...");
                pw.println("    --checkin: dump for a checkin");
                pw.println("    -f: print details of intent filters");
                pw.println("    -h: print this help");
                pw.println("  cmd may be one of:");
                pw.println("    l[ibraries]: list known shared libraries");
                pw.println("    f[ibraries]: list device features");
                pw.println("    r[esolvers]: dump intent resolvers");
                pw.println("    perm[issions]: dump permissions");
                pw.println("    pref[erred]: print preferred package settings");
                pw.println("    preferred-xml [--full]: print preferred package settings as xml");
                pw.println("    prov[iders]: dump content providers");
                pw.println("    p[ackages]: dump installed packages");
                pw.println("    s[hared-users]: dump shared user IDs");
                pw.println("    m[essages]: print collected runtime messages");
                pw.println("    v[erifiers]: print package verifier info");
                pw.println("    <package.name>: info about given package");
                pw.println("    k[eysets]: print known keysets");
                return;
            } else if ("--checkin".equals(opt)) {
                checkin = true;
            } else if ("-f".equals(opt)) {
                dumpState.setOptionEnabled(DumpState.OPTION_SHOW_FILTERS);
            } else {
                pw.println("Unknown argument: " + opt + "; use -h for help");
            }
        }

        // Is the caller requesting to dump a particular piece of data?
        if (opti < args.length) {
            String cmd = args[opti];
            opti++;
            // Is this a package name?
            if ("android".equals(cmd) || cmd.contains(".")) {
                packageName = cmd;
                // When dumping a single package, we always dump all of its
                // filter information since the amount of data will be reasonable.
                dumpState.setOptionEnabled(DumpState.OPTION_SHOW_FILTERS);
            } else if ("l".equals(cmd) || "libraries".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_LIBS);
            } else if ("f".equals(cmd) || "features".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_FEATURES);
            } else if ("r".equals(cmd) || "resolvers".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_RESOLVERS);
            } else if ("perm".equals(cmd) || "permissions".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_PERMISSIONS);
            } else if ("pref".equals(cmd) || "preferred".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_PREFERRED);
            } else if ("preferred-xml".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_PREFERRED_XML);
                if (opti < args.length && "--full".equals(args[opti])) {
                    fullPreferred = true;
                    opti++;
                }
            } else if ("p".equals(cmd) || "packages".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_PACKAGES);
            } else if ("s".equals(cmd) || "shared-users".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_SHARED_USERS);
            } else if ("prov".equals(cmd) || "providers".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_PROVIDERS);
            } else if ("m".equals(cmd) || "messages".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_MESSAGES);
            } else if ("v".equals(cmd) || "verifiers".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_VERIFIERS);
            } else if ("k".equals(cmd) || "keysets".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_KEYSETS);
            }
        }

        if (checkin) {
            pw.println("vers,1");
        }

        // reader
        synchronized (mPackages) {
            if (dumpState.isDumping(DumpState.DUMP_VERIFIERS) && packageName == null) {
                if (!checkin) {
                    if (dumpState.onTitlePrinted())
                        pw.println();
                    pw.println("Verifiers:");
                    pw.print("  Required: ");
                    pw.print(mRequiredVerifierPackage);
                    pw.print(" (uid=");
                    pw.print(getPackageUid(mRequiredVerifierPackage, 0));
                    pw.println(")");
                } else if (mRequiredVerifierPackage != null) {
                    pw.print("vrfy,"); pw.print(mRequiredVerifierPackage);
                    pw.print(","); pw.println(getPackageUid(mRequiredVerifierPackage, 0));
                }
            }

            if (dumpState.isDumping(DumpState.DUMP_LIBS) && packageName == null) {
                boolean printedHeader = false;
                final Iterator<String> it = mSharedLibraries.keySet().iterator();
                while (it.hasNext()) {
                    String name = it.next();
                    SharedLibraryEntry ent = mSharedLibraries.get(name);
                    if (!checkin) {
                        if (!printedHeader) {
                            if (dumpState.onTitlePrinted())
                                pw.println();
                            pw.println("Libraries:");
                            printedHeader = true;
                        }
                        pw.print("  ");
                    } else {
                        pw.print("lib,");
                    }
                    pw.print(name);
                    if (!checkin) {
                        pw.print(" -> ");
                    }
                    if (ent.path != null) {
                        if (!checkin) {
                            pw.print("(jar) ");
                            pw.print(ent.path);
                        } else {
                            pw.print(",jar,");
                            pw.print(ent.path);
                        }
                    } else {
                        if (!checkin) {
                            pw.print("(apk) ");
                            pw.print(ent.apk);
                        } else {
                            pw.print(",apk,");
                            pw.print(ent.apk);
                        }
                    }
                    pw.println();
                }
            }

            if (dumpState.isDumping(DumpState.DUMP_FEATURES) && packageName == null) {
                if (dumpState.onTitlePrinted())
                    pw.println();
                if (!checkin) {
                    pw.println("Features:");
                }
                Iterator<String> it = mAvailableFeatures.keySet().iterator();
                while (it.hasNext()) {
                    String name = it.next();
                    if (!checkin) {
                        pw.print("  ");
                    } else {
                        pw.print("feat,");
                    }
                    pw.println(name);
                }
            }

            if (!checkin && dumpState.isDumping(DumpState.DUMP_RESOLVERS)) {
                if (mActivities.dump(pw, dumpState.getTitlePrinted() ? "\nActivity Resolver Table:"
                        : "Activity Resolver Table:", "  ", packageName,
                        dumpState.isOptionEnabled(DumpState.OPTION_SHOW_FILTERS))) {
                    dumpState.setTitlePrinted(true);
                }
                if (mReceivers.dump(pw, dumpState.getTitlePrinted() ? "\nReceiver Resolver Table:"
                        : "Receiver Resolver Table:", "  ", packageName,
                        dumpState.isOptionEnabled(DumpState.OPTION_SHOW_FILTERS))) {
                    dumpState.setTitlePrinted(true);
                }
                if (mServices.dump(pw, dumpState.getTitlePrinted() ? "\nService Resolver Table:"
                        : "Service Resolver Table:", "  ", packageName,
                        dumpState.isOptionEnabled(DumpState.OPTION_SHOW_FILTERS))) {
                    dumpState.setTitlePrinted(true);
                }
                if (mProviders.dump(pw, dumpState.getTitlePrinted() ? "\nProvider Resolver Table:"
                        : "Provider Resolver Table:", "  ", packageName,
                        dumpState.isOptionEnabled(DumpState.OPTION_SHOW_FILTERS))) {
                    dumpState.setTitlePrinted(true);
                }
            }

            if (!checkin && dumpState.isDumping(DumpState.DUMP_PREFERRED)) {
                for (int i=0; i<mSettings.mPreferredActivities.size(); i++) {
                    PreferredIntentResolver pir = mSettings.mPreferredActivities.valueAt(i);
                    int user = mSettings.mPreferredActivities.keyAt(i);
                    if (pir.dump(pw,
                            dumpState.getTitlePrinted()
                                ? "\nPreferred Activities User " + user + ":"
                                : "Preferred Activities User " + user + ":", "  ",
                            packageName, true)) {
                        dumpState.setTitlePrinted(true);
                    }
                }
            }

            if (!checkin && dumpState.isDumping(DumpState.DUMP_PREFERRED_XML)) {
                pw.flush();
                FileOutputStream fout = new FileOutputStream(fd);
                BufferedOutputStream str = new BufferedOutputStream(fout);
                XmlSerializer serializer = new FastXmlSerializer();
                try {
                    serializer.setOutput(str, "utf-8");
                    serializer.startDocument(null, true);
                    serializer.setFeature(
                            "http://xmlpull.org/v1/doc/features.html#indent-output", true);
                    mSettings.writePreferredActivitiesLPr(serializer, 0, fullPreferred);
                    serializer.endDocument();
                    serializer.flush();
                } catch (IllegalArgumentException e) {
                    pw.println("Failed writing: " + e);
                } catch (IllegalStateException e) {
                    pw.println("Failed writing: " + e);
                } catch (IOException e) {
                    pw.println("Failed writing: " + e);
                }
            }

            if (!checkin && dumpState.isDumping(DumpState.DUMP_PERMISSIONS)) {
                mSettings.dumpPermissionsLPr(pw, packageName, dumpState);
            }

            if (!checkin && dumpState.isDumping(DumpState.DUMP_PROVIDERS)) {
                boolean printedSomething = false;
                for (PackageParser.Provider p : mProviders.mProviders.values()) {
                    if (packageName != null && !packageName.equals(p.info.packageName)) {
                        continue;
                    }
                    if (!printedSomething) {
                        if (dumpState.onTitlePrinted())
                            pw.println();
                        pw.println("Registered ContentProviders:");
                        printedSomething = true;
                    }
                    pw.print("  "); p.printComponentShortName(pw); pw.println(":");
                    pw.print("    "); pw.println(p.toString());
                }
                printedSomething = false;
                for (Map.Entry<String, PackageParser.Provider> entry :
                        mProvidersByAuthority.entrySet()) {
                    PackageParser.Provider p = entry.getValue();
                    if (packageName != null && !packageName.equals(p.info.packageName)) {
                        continue;
                    }
                    if (!printedSomething) {
                        if (dumpState.onTitlePrinted())
                            pw.println();
                        pw.println("ContentProvider Authorities:");
                        printedSomething = true;
                    }
                    pw.print("  ["); pw.print(entry.getKey()); pw.println("]:");
                    pw.print("    "); pw.println(p.toString());
                    if (p.info != null && p.info.applicationInfo != null) {
                        final String appInfo = p.info.applicationInfo.toString();
                        pw.print("      applicationInfo="); pw.println(appInfo);
                    }
                }
            }

            if (!checkin && dumpState.isDumping(DumpState.DUMP_KEYSETS)) {
                mSettings.mKeySetManager.dump(pw, packageName, dumpState);
            }

            if (dumpState.isDumping(DumpState.DUMP_PACKAGES)) {
                mSettings.dumpPackagesLPr(pw, packageName, dumpState, checkin);
            }

            if (!checkin && dumpState.isDumping(DumpState.DUMP_SHARED_USERS)) {
                mSettings.dumpSharedUsersLPr(pw, packageName, dumpState);
            }

            if (!checkin && dumpState.isDumping(DumpState.DUMP_MESSAGES) && packageName == null) {
                if (dumpState.onTitlePrinted())
                    pw.println();
                mSettings.dumpReadMessagesLPr(pw, dumpState);

                pw.println();
                pw.println("Package warning messages:");
                final File fname = getSettingsProblemFile();
                FileInputStream in = null;
                try {
                    in = new FileInputStream(fname);
                    final int avail = in.available();
                    final byte[] data = new byte[avail];
                    in.read(data);
                    pw.print(new String(data));
                } catch (FileNotFoundException e) {
                } catch (IOException e) {
                } finally {
                    if (in != null) {
                        try {
                            in.close();
                        } catch (IOException e) {
                        }
                    }
                }
            }
        }
    }

    // ------- apps on sdcard specific code -------
    static final boolean DEBUG_SD_INSTALL = false;

    private static final String SD_ENCRYPTION_KEYSTORE_NAME = "AppsOnSD";

    private static final String SD_ENCRYPTION_ALGORITHM = "AES";

    private boolean mMediaMounted = false;

    private String getEncryptKey() {
        try {
            String sdEncKey = SystemKeyStore.getInstance().retrieveKeyHexString(
                    SD_ENCRYPTION_KEYSTORE_NAME);
            if (sdEncKey == null) {
                sdEncKey = SystemKeyStore.getInstance().generateNewKeyHexString(128,
                        SD_ENCRYPTION_ALGORITHM, SD_ENCRYPTION_KEYSTORE_NAME);
                if (sdEncKey == null) {
                    Slog.e(TAG, "Failed to create encryption keys");
                    return null;
                }
            }
            return sdEncKey;
        } catch (NoSuchAlgorithmException nsae) {
            Slog.e(TAG, "Failed to create encryption keys with exception: " + nsae);
            return null;
        } catch (IOException ioe) {
            Slog.e(TAG, "Failed to retrieve encryption keys with exception: " + ioe);
            return null;
        }

    }

    /* package */static String getTempContainerId() {
        int tmpIdx = 1;
        String list[] = PackageHelper.getSecureContainerList();
        if (list != null) {
            for (final String name : list) {
                // Ignore null and non-temporary container entries
                if (name == null || !name.startsWith(mTempContainerPrefix)) {
                    continue;
                }

                String subStr = name.substring(mTempContainerPrefix.length());
                try {
                    int cid = Integer.parseInt(subStr);
                    if (cid >= tmpIdx) {
                        tmpIdx = cid + 1;
                    }
                } catch (NumberFormatException e) {
                }
            }
        }
        return mTempContainerPrefix + tmpIdx;
    }

    /*
     * Update media status on PackageManager.
     */
    @Override
    public void updateExternalMediaStatus(final boolean mediaStatus, final boolean reportStatus) {
        int callingUid = Binder.getCallingUid();
        if (callingUid != 0 && callingUid != Process.SYSTEM_UID) {
            throw new SecurityException("Media status can only be updated by the system");
        }
        // reader; this apparently protects mMediaMounted, but should probably
        // be a different lock in that case.
        synchronized (mPackages) {
            Log.i(TAG, "Updating external media status from "
                    + (mMediaMounted ? "mounted" : "unmounted") + " to "
                    + (mediaStatus ? "mounted" : "unmounted"));
            if (DEBUG_SD_INSTALL)
                Log.i(TAG, "updateExternalMediaStatus:: mediaStatus=" + mediaStatus
                        + ", mMediaMounted=" + mMediaMounted);
            if (mediaStatus == mMediaMounted) {
                final Message msg = mHandler.obtainMessage(UPDATED_MEDIA_STATUS, reportStatus ? 1
                        : 0, -1);
                mHandler.sendMessage(msg);
                return;
            }
            mMediaMounted = mediaStatus;
        }
        // Queue up an async operation since the package installation may take a
        // little while.
        mHandler.post(new Runnable() {
            public void run() {
                updateExternalMediaStatusInner(mediaStatus, reportStatus, true);
            }
        });
    }

    /**
     * Called by MountService when the initial ASECs to scan are available.
     * Should block until all the ASEC containers are finished being scanned.
     */
    public void scanAvailableAsecs() {
        updateExternalMediaStatusInner(true, false, false);
        if (mShouldRestoreconData) {
            SELinuxMMAC.setRestoreconDone();
            mShouldRestoreconData = false;
        }
    }

    /*
     * Collect information of applications on external media, map them against
     * existing containers and update information based on current mount status.
     * Please note that we always have to report status if reportStatus has been
     * set to true especially when unloading packages.
     */
    private void updateExternalMediaStatusInner(boolean isMounted, boolean reportStatus,
            boolean externalStorage) {
        // Collection of uids
        int uidArr[] = null;
        // Collection of stale containers
        HashSet<String> removeCids = new HashSet<String>();
        // Collection of packages on external media with valid containers.
        HashMap<AsecInstallArgs, String> processCids = new HashMap<AsecInstallArgs, String>();
        // Get list of secure containers.
        final String list[] = PackageHelper.getSecureContainerList();
        if (list == null || list.length == 0) {
            Log.i(TAG, "No secure containers on sdcard");
        } else {
            // Process list of secure containers and categorize them
            // as active or stale based on their package internal state.
            int uidList[] = new int[list.length];
            int num = 0;
            // reader
            synchronized (mPackages) {
                for (String cid : list) {
                    if (DEBUG_SD_INSTALL)
                        Log.i(TAG, "Processing container " + cid);
                    String pkgName = getAsecPackageName(cid);
                    if (pkgName == null) {
                        if (DEBUG_SD_INSTALL)
                            Log.i(TAG, "Container : " + cid + " stale");
                        removeCids.add(cid);
                        continue;
                    }
                    if (DEBUG_SD_INSTALL)
                        Log.i(TAG, "Looking for pkg : " + pkgName);

                    final PackageSetting ps = mSettings.mPackages.get(pkgName);
                    if (ps == null) {
                        Log.i(TAG, "Deleting container with no matching settings " + cid);
                        removeCids.add(cid);
                        continue;
                    }

                    /*
                     * Skip packages that are not external if we're unmounting
                     * external storage.
                     */
                    if (externalStorage && !isMounted && !isExternal(ps)) {
                        continue;
                    }

                    final AsecInstallArgs args = new AsecInstallArgs(cid,
                            getAppInstructionSetFromSettings(ps),
                            isForwardLocked(ps));
                    // The package status is changed only if the code path
                    // matches between settings and the container id.
                    if (ps.codePathString != null && ps.codePathString.equals(args.getCodePath())) {
                        if (DEBUG_SD_INSTALL) {
                            Log.i(TAG, "Container : " + cid + " corresponds to pkg : " + pkgName
                                    + " at code path: " + ps.codePathString);
                        }

                        // We do have a valid package installed on sdcard
                        processCids.put(args, ps.codePathString);
                        final int uid = ps.appId;
                        if (uid != -1) {
                            uidList[num++] = uid;
                        }
                    } else {
                        Log.i(TAG, "Deleting stale container for " + cid);
                        removeCids.add(cid);
                    }
                }
            }

            if (num > 0) {
                // Sort uid list
                Arrays.sort(uidList, 0, num);
                // Throw away duplicates
                uidArr = new int[num];
                uidArr[0] = uidList[0];
                int di = 0;
                for (int i = 1; i < num; i++) {
                    if (uidList[i - 1] != uidList[i]) {
                        uidArr[di++] = uidList[i];
                    }
                }
            }
        }
        // Process packages with valid entries.
        if (isMounted) {
            if (DEBUG_SD_INSTALL)
                Log.i(TAG, "Loading packages");
            loadMediaPackages(processCids, uidArr, removeCids);
            startCleaningPackages();
        } else {
            if (DEBUG_SD_INSTALL)
                Log.i(TAG, "Unloading packages");
            unloadMediaPackages(processCids, uidArr, reportStatus);
        }
    }

   private void sendResourcesChangedBroadcast(boolean mediaStatus, boolean replacing,
           ArrayList<String> pkgList, int uidArr[], IIntentReceiver finishedReceiver) {
        int size = pkgList.size();
        if (size > 0) {
            // Send broadcasts here
            Bundle extras = new Bundle();
            extras.putStringArray(Intent.EXTRA_CHANGED_PACKAGE_LIST, pkgList
                    .toArray(new String[size]));
            if (uidArr != null) {
                extras.putIntArray(Intent.EXTRA_CHANGED_UID_LIST, uidArr);
            }
            if (replacing) {
                extras.putBoolean(Intent.EXTRA_REPLACING, replacing);
            }
            String action = mediaStatus ? Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE
                    : Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE;
            sendPackageBroadcast(action, null, extras, null, finishedReceiver, null);
        }
    }

   /*
     * Look at potentially valid container ids from processCids If package
     * information doesn't match the one on record or package scanning fails,
     * the cid is added to list of removeCids. We currently don't delete stale
     * containers.
     */
   private void loadMediaPackages(HashMap<AsecInstallArgs, String> processCids, int uidArr[],
            HashSet<String> removeCids) {
        ArrayList<String> pkgList = new ArrayList<String>();
        Set<AsecInstallArgs> keys = processCids.keySet();
        boolean doGc = false;
        for (AsecInstallArgs args : keys) {
            String codePath = processCids.get(args);
            if (DEBUG_SD_INSTALL)
                Log.i(TAG, "Loading container : " + args.cid);
            int retCode = PackageManager.INSTALL_FAILED_CONTAINER_ERROR;
            try {
                // Make sure there are no container errors first.
                if (args.doPreInstall(PackageManager.INSTALL_SUCCEEDED) != PackageManager.INSTALL_SUCCEEDED) {
                    Slog.e(TAG, "Failed to mount cid : " + args.cid
                            + " when installing from sdcard");
                    continue;
                }
                // Check code path here.
                if (codePath == null || !codePath.equals(args.getCodePath())) {
                    Slog.e(TAG, "Container " + args.cid + " cachepath " + args.getCodePath()
                            + " does not match one in settings " + codePath);
                    continue;
                }
                // Parse package
                int parseFlags = mDefParseFlags;
                if (args.isExternal()) {
                    parseFlags |= PackageParser.PARSE_ON_SDCARD;
                }
                if (args.isFwdLocked()) {
                    parseFlags |= PackageParser.PARSE_FORWARD_LOCK;
                }

                doGc = true;
                synchronized (mInstallLock) {
                    final PackageParser.Package pkg = scanPackageLI(new File(codePath), parseFlags,
                            0, 0, null, null);
                    // Scan the package
                    if (pkg != null) {
                        /*
                         * TODO why is the lock being held? doPostInstall is
                         * called in other places without the lock. This needs
                         * to be straightened out.
                         */
                        // writer
                        synchronized (mPackages) {
                            retCode = PackageManager.INSTALL_SUCCEEDED;
                            pkgList.add(pkg.packageName);
                            // Post process args
                            args.doPostInstall(PackageManager.INSTALL_SUCCEEDED,
                                    pkg.applicationInfo.uid);
                        }
                    } else {
                        Slog.i(TAG, "Failed to install pkg from  " + codePath + " from sdcard");
                    }
                }

            } finally {
                if (retCode != PackageManager.INSTALL_SUCCEEDED) {
                    // Don't destroy container here. Wait till gc clears things
                    // up.
                    removeCids.add(args.cid);
                }
            }
        }
        // writer
        synchronized (mPackages) {
            // If the platform SDK has changed since the last time we booted,
            // we need to re-grant app permission to catch any new ones that
            // appear. This is really a hack, and means that apps can in some
            // cases get permissions that the user didn't initially explicitly
            // allow... it would be nice to have some better way to handle
            // this situation.
            final boolean regrantPermissions = mSettings.mExternalSdkPlatform != mSdkVersion;
            if (regrantPermissions)
                Slog.i(TAG, "Platform changed from " + mSettings.mExternalSdkPlatform + " to "
                        + mSdkVersion + "; regranting permissions for external storage");
            mSettings.mExternalSdkPlatform = mSdkVersion;

            // Make sure group IDs have been assigned, and any permission
            // changes in other apps are accounted for
            updatePermissionsLPw(null, null, UPDATE_PERMISSIONS_ALL
                    | (regrantPermissions
                            ? (UPDATE_PERMISSIONS_REPLACE_PKG|UPDATE_PERMISSIONS_REPLACE_ALL)
                            : 0));
            // can downgrade to reader
            // Persist settings
            mSettings.writeLPr();
        }
        // Send a broadcast to let everyone know we are done processing
        if (pkgList.size() > 0) {
            sendResourcesChangedBroadcast(true, false, pkgList, uidArr, null);
        }
        // Force gc to avoid any stale parser references that we might have.
        if (doGc) {
            Runtime.getRuntime().gc();
        }
        // List stale containers and destroy stale temporary containers.
        if (removeCids != null) {
            for (String cid : removeCids) {
                if (cid.startsWith(mTempContainerPrefix)) {
                    Log.i(TAG, "Destroying stale temporary container " + cid);
                    PackageHelper.destroySdDir(cid);
                } else {
                    Log.w(TAG, "Container " + cid + " is stale");
               }
           }
        }
    }

   /*
     * Utility method to unload a list of specified containers
     */
    private void unloadAllContainers(Set<AsecInstallArgs> cidArgs) {
        // Just unmount all valid containers.
        for (AsecInstallArgs arg : cidArgs) {
            synchronized (mInstallLock) {
                arg.doPostDeleteLI(false);
           }
       }
   }

    /*
     * Unload packages mounted on external media. This involves deleting package
     * data from internal structures, sending broadcasts about diabled packages,
     * gc'ing to free up references, unmounting all secure containers
     * corresponding to packages on external media, and posting a
     * UPDATED_MEDIA_STATUS message if status has been requested. Please note
     * that we always have to post this message if status has been requested no
     * matter what.
     */
    private void unloadMediaPackages(HashMap<AsecInstallArgs, String> processCids, int uidArr[],
            final boolean reportStatus) {
        if (DEBUG_SD_INSTALL)
            Log.i(TAG, "unloading media packages");
        ArrayList<String> pkgList = new ArrayList<String>();
        ArrayList<AsecInstallArgs> failedList = new ArrayList<AsecInstallArgs>();
        final Set<AsecInstallArgs> keys = processCids.keySet();
        for (AsecInstallArgs args : keys) {
            String pkgName = args.getPackageName();
            if (DEBUG_SD_INSTALL)
                Log.i(TAG, "Trying to unload pkg : " + pkgName);
            // Delete package internally
            PackageRemovedInfo outInfo = new PackageRemovedInfo();
            synchronized (mInstallLock) {
                boolean res = deletePackageLI(pkgName, null, false, null, null,
                        PackageManager.DELETE_KEEP_DATA, outInfo, false);
                if (res) {
                    pkgList.add(pkgName);
                } else {
                    Slog.e(TAG, "Failed to delete pkg from sdcard : " + pkgName);
                    failedList.add(args);
                }
            }
        }

        // reader
        synchronized (mPackages) {
            // We didn't update the settings after removing each package;
            // write them now for all packages.
            mSettings.writeLPr();
        }

        // We have to absolutely send UPDATED_MEDIA_STATUS only
        // after confirming that all the receivers processed the ordered
        // broadcast when packages get disabled, force a gc to clean things up.
        // and unload all the containers.
        if (pkgList.size() > 0) {
            sendResourcesChangedBroadcast(false, false, pkgList, uidArr,
                    new IIntentReceiver.Stub() {
                public void performReceive(Intent intent, int resultCode, String data,
                        Bundle extras, boolean ordered, boolean sticky,
                        int sendingUser) throws RemoteException {
                    Message msg = mHandler.obtainMessage(UPDATED_MEDIA_STATUS,
                            reportStatus ? 1 : 0, 1, keys);
                    mHandler.sendMessage(msg);
                }
            });
        } else {
            Message msg = mHandler.obtainMessage(UPDATED_MEDIA_STATUS, reportStatus ? 1 : 0, -1,
                    keys);
            mHandler.sendMessage(msg);
        }
    }

    /** Binder call */
    @Override
    public void movePackage(final String packageName, final IPackageMoveObserver observer,
            final int flags) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MOVE_PACKAGE, null);
        UserHandle user = new UserHandle(UserHandle.getCallingUserId());
        int returnCode = PackageManager.MOVE_SUCCEEDED;
        int currFlags = 0;
        int newFlags = 0;
        // reader
        synchronized (mPackages) {
            PackageParser.Package pkg = mPackages.get(packageName);
            if (pkg == null) {
                returnCode = PackageManager.MOVE_FAILED_DOESNT_EXIST;
            } else {
                // Disable moving fwd locked apps and system packages
                if (pkg.applicationInfo != null && isSystemApp(pkg)) {
                    Slog.w(TAG, "Cannot move system application");
                    returnCode = PackageManager.MOVE_FAILED_SYSTEM_PACKAGE;
                } else if (pkg.mOperationPending) {
                    Slog.w(TAG, "Attempt to move package which has pending operations");
                    returnCode = PackageManager.MOVE_FAILED_OPERATION_PENDING;
                } else {
                    // Find install location first
                    if ((flags & PackageManager.MOVE_EXTERNAL_MEDIA) != 0
                            && (flags & PackageManager.MOVE_INTERNAL) != 0) {
                        Slog.w(TAG, "Ambigous flags specified for move location.");
                        returnCode = PackageManager.MOVE_FAILED_INVALID_LOCATION;
                    } else {
                        newFlags = (flags & PackageManager.MOVE_EXTERNAL_MEDIA) != 0 ? PackageManager.INSTALL_EXTERNAL
                                : PackageManager.INSTALL_INTERNAL;
                        currFlags = isExternal(pkg) ? PackageManager.INSTALL_EXTERNAL
                                : PackageManager.INSTALL_INTERNAL;

                        if (newFlags == currFlags) {
                            Slog.w(TAG, "No move required. Trying to move to same location");
                            returnCode = PackageManager.MOVE_FAILED_INVALID_LOCATION;
                        } else {
                            if (isForwardLocked(pkg)) {
                                currFlags |= PackageManager.INSTALL_FORWARD_LOCK;
                                newFlags |= PackageManager.INSTALL_FORWARD_LOCK;
                            }
                        }
                    }
                    if (returnCode == PackageManager.MOVE_SUCCEEDED) {
                        pkg.mOperationPending = true;
                    }
                }
            }

            /*
             * TODO this next block probably shouldn't be inside the lock. We
             * can't guarantee these won't change after this is fired off
             * anyway.
             */
            if (returnCode != PackageManager.MOVE_SUCCEEDED) {
                processPendingMove(new MoveParams(null, observer, 0, packageName, null,
                        null, -1, user),
                        returnCode);
            } else {
                Message msg = mHandler.obtainMessage(INIT_COPY);
                final String instructionSet = getAppInstructionSet(pkg.applicationInfo);
                InstallArgs srcArgs = createInstallArgs(currFlags, pkg.applicationInfo.sourceDir,
                        pkg.applicationInfo.publicSourceDir, pkg.applicationInfo.nativeLibraryDir,
                        instructionSet);
                MoveParams mp = new MoveParams(srcArgs, observer, newFlags, packageName,
                        pkg.applicationInfo.dataDir, instructionSet, pkg.applicationInfo.uid, user);
                msg.obj = mp;
                mHandler.sendMessage(msg);
            }
        }
    }

    private void processPendingMove(final MoveParams mp, final int currentStatus) {
        // Queue up an async operation since the package deletion may take a
        // little while.
        mHandler.post(new Runnable() {
            public void run() {
                // TODO fix this; this does nothing.
                mHandler.removeCallbacks(this);
                int returnCode = currentStatus;
                if (currentStatus == PackageManager.MOVE_SUCCEEDED) {
                    int uidArr[] = null;
                    ArrayList<String> pkgList = null;
                    synchronized (mPackages) {
                        PackageParser.Package pkg = mPackages.get(mp.packageName);
                        if (pkg == null) {
                            Slog.w(TAG, " Package " + mp.packageName
                                    + " doesn't exist. Aborting move");
                            returnCode = PackageManager.MOVE_FAILED_DOESNT_EXIST;
                        } else if (!mp.srcArgs.getCodePath().equals(pkg.applicationInfo.sourceDir)) {
                            Slog.w(TAG, "Package " + mp.packageName + " code path changed from "
                                    + mp.srcArgs.getCodePath() + " to "
                                    + pkg.applicationInfo.sourceDir
                                    + " Aborting move and returning error");
                            returnCode = PackageManager.MOVE_FAILED_INTERNAL_ERROR;
                        } else {
                            uidArr = new int[] {
                                pkg.applicationInfo.uid
                            };
                            pkgList = new ArrayList<String>();
                            pkgList.add(mp.packageName);
                        }
                    }
                    if (returnCode == PackageManager.MOVE_SUCCEEDED) {
                        // Send resources unavailable broadcast
                        sendResourcesChangedBroadcast(false, true, pkgList, uidArr, null);
                        // Update package code and resource paths
                        synchronized (mInstallLock) {
                            synchronized (mPackages) {
                                PackageParser.Package pkg = mPackages.get(mp.packageName);
                                // Recheck for package again.
                                if (pkg == null) {
                                    Slog.w(TAG, " Package " + mp.packageName
                                            + " doesn't exist. Aborting move");
                                    returnCode = PackageManager.MOVE_FAILED_DOESNT_EXIST;
                                } else if (!mp.srcArgs.getCodePath().equals(
                                        pkg.applicationInfo.sourceDir)) {
                                    Slog.w(TAG, "Package " + mp.packageName
                                            + " code path changed from " + mp.srcArgs.getCodePath()
                                            + " to " + pkg.applicationInfo.sourceDir
                                            + " Aborting move and returning error");
                                    returnCode = PackageManager.MOVE_FAILED_INTERNAL_ERROR;
                                } else {
                                    final String oldCodePath = pkg.mPath;
                                    final String newCodePath = mp.targetArgs.getCodePath();
                                    final String newResPath = mp.targetArgs.getResourcePath();
                                    final String newNativePath = mp.targetArgs
                                            .getNativeLibraryPath();

                                    final File newNativeDir = new File(newNativePath);

                                    if (!isForwardLocked(pkg) && !isExternal(pkg)) {
                                        ApkHandle handle = null;
                                        try {
                                            handle = ApkHandle.create(newCodePath);
                                            final int abi = NativeLibraryHelper.findSupportedAbi(
                                                    handle, Build.SUPPORTED_ABIS);
                                            if (abi >= 0) {
                                                NativeLibraryHelper.copyNativeBinariesIfNeededLI(
                                                        handle, newNativeDir, Build.SUPPORTED_ABIS[abi]);
                                            }
                                        } catch (IOException ioe) {
                                            Slog.w(TAG, "Unable to extract native libs for package :"
                                                    + mp.packageName, ioe);
                                            returnCode = PackageManager.MOVE_FAILED_INTERNAL_ERROR;
                                        } finally {
                                            IoUtils.closeQuietly(handle);
                                        }
                                    }
                                    final int[] users = sUserManager.getUserIds();
                                    if (returnCode == PackageManager.MOVE_SUCCEEDED) {
                                        for (int user : users) {
                                            if (mInstaller.linkNativeLibraryDirectory(pkg.packageName,
                                                    newNativePath, user) < 0) {
                                                returnCode = PackageManager.MOVE_FAILED_INSUFFICIENT_STORAGE;
                                            }
                                        }
                                    }

                                    if (returnCode == PackageManager.MOVE_SUCCEEDED) {
                                        pkg.mPath = newCodePath;
                                        // Move dex files around
                                        if (moveDexFilesLI(pkg) != PackageManager.INSTALL_SUCCEEDED) {
                                            // Moving of dex files failed. Set
                                            // error code and abort move.
                                            pkg.mPath = pkg.mScanPath;
                                            returnCode = PackageManager.MOVE_FAILED_INSUFFICIENT_STORAGE;
                                        }
                                    }

                                    if (returnCode == PackageManager.MOVE_SUCCEEDED) {
                                        pkg.mScanPath = newCodePath;
                                        pkg.applicationInfo.sourceDir = newCodePath;
                                        pkg.applicationInfo.publicSourceDir = newResPath;
                                        pkg.applicationInfo.nativeLibraryDir = newNativePath;
                                        PackageSetting ps = (PackageSetting) pkg.mExtras;
                                        ps.codePath = new File(pkg.applicationInfo.sourceDir);
                                        ps.codePathString = ps.codePath.getPath();
                                        ps.resourcePath = new File(
                                                pkg.applicationInfo.publicSourceDir);
                                        ps.resourcePathString = ps.resourcePath.getPath();
                                        ps.nativeLibraryPathString = newNativePath;
                                        // Set the application info flag
                                        // correctly.
                                        if ((mp.flags & PackageManager.INSTALL_EXTERNAL) != 0) {
                                            pkg.applicationInfo.flags |= ApplicationInfo.FLAG_EXTERNAL_STORAGE;
                                        } else {
                                            pkg.applicationInfo.flags &= ~ApplicationInfo.FLAG_EXTERNAL_STORAGE;
                                        }
                                        ps.setFlags(pkg.applicationInfo.flags);
                                        mAppDirs.remove(oldCodePath);
                                        mAppDirs.put(newCodePath, pkg);
                                        // Persist settings
                                        mSettings.writeLPr();
                                    }
                                }
                            }
                        }
                        // Send resources available broadcast
                        sendResourcesChangedBroadcast(true, false, pkgList, uidArr, null);
                    }
                }
                if (returnCode != PackageManager.MOVE_SUCCEEDED) {
                    // Clean up failed installation
                    if (mp.targetArgs != null) {
                        mp.targetArgs.doPostInstall(PackageManager.INSTALL_FAILED_INTERNAL_ERROR,
                                -1);
                    }
                } else {
                    // Force a gc to clear things up.
                    Runtime.getRuntime().gc();
                    // Delete older code
                    synchronized (mInstallLock) {
                        mp.srcArgs.doPostDeleteLI(true);
                    }
                }

                // Allow more operations on this file if we didn't fail because
                // an operation was already pending for this package.
                if (returnCode != PackageManager.MOVE_FAILED_OPERATION_PENDING) {
                    synchronized (mPackages) {
                        PackageParser.Package pkg = mPackages.get(mp.packageName);
                        if (pkg != null) {
                            pkg.mOperationPending = false;
                       }
                   }
                }

                IPackageMoveObserver observer = mp.observer;
                if (observer != null) {
                    try {
                        observer.packageMoved(mp.packageName, returnCode);
                    } catch (RemoteException e) {
                        Log.i(TAG, "Observer no longer exists.");
                    }
                }
            }
        });
    }

    @Override
    public boolean setInstallLocation(int loc) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS,
                null);
        if (getInstallLocation() == loc) {
            return true;
        }
        if (loc == PackageHelper.APP_INSTALL_AUTO || loc == PackageHelper.APP_INSTALL_INTERNAL
                || loc == PackageHelper.APP_INSTALL_EXTERNAL) {
            android.provider.Settings.Global.putInt(mContext.getContentResolver(),
                    android.provider.Settings.Global.DEFAULT_INSTALL_LOCATION, loc);
            return true;
        }
        return false;
   }

    @Override
    public int getInstallLocation() {
        return android.provider.Settings.Global.getInt(mContext.getContentResolver(),
                android.provider.Settings.Global.DEFAULT_INSTALL_LOCATION,
                PackageHelper.APP_INSTALL_AUTO);
    }

    /** Called by UserManagerService */
    void cleanUpUserLILPw(int userHandle) {
        mDirtyUsers.remove(userHandle);
        mSettings.removeUserLPr(userHandle);
        mPendingBroadcasts.remove(userHandle);
        if (mInstaller != null) {
            // Technically, we shouldn't be doing this with the package lock
            // held.  However, this is very rare, and there is already so much
            // other disk I/O going on, that we'll let it slide for now.
            mInstaller.removeUserDataDirs(userHandle);
        }
    }

    /** Called by UserManagerService */
    void createNewUserLILPw(int userHandle, File path) {
        if (mInstaller != null) {
            mInstaller.createUserConfig(userHandle);
            mSettings.createNewUserLILPw(this, mInstaller, userHandle, path);
        }
    }

    @Override
    public VerifierDeviceIdentity getVerifierDeviceIdentity() throws RemoteException {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.PACKAGE_VERIFICATION_AGENT,
                "Only package verification agents can read the verifier device identity");

        synchronized (mPackages) {
            return mSettings.getVerifierDeviceIdentityLPw();
        }
    }

    @Override
    public void setPermissionEnforced(String permission, boolean enforced) {
        mContext.enforceCallingOrSelfPermission(GRANT_REVOKE_PERMISSIONS, null);
        if (READ_EXTERNAL_STORAGE.equals(permission)) {
            synchronized (mPackages) {
                if (mSettings.mReadExternalStorageEnforced == null
                        || mSettings.mReadExternalStorageEnforced != enforced) {
                    mSettings.mReadExternalStorageEnforced = enforced;
                    mSettings.writeLPr();
                }
            }
            // kill any non-foreground processes so we restart them and
            // grant/revoke the GID.
            final IActivityManager am = ActivityManagerNative.getDefault();
            if (am != null) {
                final long token = Binder.clearCallingIdentity();
                try {
                    am.killProcessesBelowForeground("setPermissionEnforcement");
                } catch (RemoteException e) {
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
        } else {
            throw new IllegalArgumentException("No selective enforcement for " + permission);
        }
    }

    @Override
    @Deprecated
    public boolean isPermissionEnforced(String permission) {
        return true;
    }

    @Override
    public boolean isStorageLow() {
        final long token = Binder.clearCallingIdentity();
        try {
            final DeviceStorageMonitorService dsm = (DeviceStorageMonitorService) ServiceManager
                    .getService(DeviceStorageMonitorService.SERVICE);
            return dsm.isMemoryLow();
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }
}
