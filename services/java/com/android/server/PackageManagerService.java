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

package com.android.server;

import com.android.internal.app.IMediaContainerService;
import com.android.internal.app.ResolverActivity;
import com.android.internal.content.PackageHelper;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.JournaledFile;
import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.app.admin.IDevicePolicyManager;
import android.app.backup.IBackupManager;
import android.content.Context;
import android.content.ComponentName;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.ServiceConnection;
import android.content.IntentSender.SendIntentException;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.IPackageDataObserver;
import android.content.pm.IPackageDeleteObserver;
import android.content.pm.IPackageInstallObserver;
import android.content.pm.IPackageManager;
import android.content.pm.IPackageMoveObserver;
import android.content.pm.IPackageStatsObserver;
import android.content.pm.InstrumentationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInfoLite;
import android.content.pm.PackageManager;
import android.content.pm.PackageStats;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
import android.content.pm.PackageParser;
import android.content.pm.PermissionInfo;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.Environment;
import android.os.FileObserver;
import android.os.FileUtils;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.Settings;
import android.security.SystemKeyStore;
import android.util.*;
import android.view.Display;
import android.view.WindowManager;

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
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

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
 */
class PackageManagerService extends IPackageManager.Stub {
    private static final String TAG = "PackageManager";
    private static final boolean DEBUG_SETTINGS = false;
    private static final boolean DEBUG_PREFERRED = false;
    private static final boolean DEBUG_UPGRADE = false;
    private static final boolean DEBUG_INSTALL = false;
    private static final boolean DEBUG_NATIVE = false;

    private static final boolean MULTIPLE_APPLICATION_UIDS = true;
    private static final int RADIO_UID = Process.PHONE_UID;
    private static final int LOG_UID = Process.LOG_UID;
    private static final int FIRST_APPLICATION_UID =
        Process.FIRST_APPLICATION_UID;
    private static final int MAX_APPLICATION_UIDS = 1000;

    private static final boolean SHOW_INFO = false;

    private static final boolean GET_CERTIFICATES = true;

    private static final int REMOVE_EVENTS =
        FileObserver.CLOSE_WRITE | FileObserver.DELETE | FileObserver.MOVED_FROM;
    private static final int ADD_EVENTS =
        FileObserver.CLOSE_WRITE /*| FileObserver.CREATE*/ | FileObserver.MOVED_TO;

    private static final int OBSERVER_EVENTS = REMOVE_EVENTS | ADD_EVENTS;
    // Suffix used during package installation when copying/moving
    // package apks to install directory.
    private static final String INSTALL_PACKAGE_SUFFIX = "-";

    /**
     * Indicates the state of installation. Used by PackageManager to
     * figure out incomplete installations. Say a package is being installed
     * (the state is set to PKG_INSTALL_INCOMPLETE) and remains so till
     * the package installation is successful or unsuccesful lin which case
     * the PackageManager will no longer maintain state information associated
     * with the package. If some exception(like device freeze or battery being
     * pulled out) occurs during installation of a package, the PackageManager
     * needs this information to clean up the previously failed installation.
     */
    private static final int PKG_INSTALL_INCOMPLETE = 0;
    private static final int PKG_INSTALL_COMPLETE = 1;

    static final int SCAN_MONITOR = 1<<0;
    static final int SCAN_NO_DEX = 1<<1;
    static final int SCAN_FORCE_DEX = 1<<2;
    static final int SCAN_UPDATE_SIGNATURE = 1<<3;
    static final int SCAN_NEW_INSTALL = 1<<4;
    static final int SCAN_NO_PATHS = 1<<5;

    static final int REMOVE_CHATTY = 1<<16;
    
    static final ComponentName DEFAULT_CONTAINER_COMPONENT = new ComponentName(
            "com.android.defcontainer",
            "com.android.defcontainer.DefaultContainerService");
    
    final HandlerThread mHandlerThread = new HandlerThread("PackageManager",
            Process.THREAD_PRIORITY_BACKGROUND);
    final PackageHandler mHandler;

    final int mSdkVersion = Build.VERSION.SDK_INT;
    final String mSdkCodename = "REL".equals(Build.VERSION.CODENAME)
            ? null : Build.VERSION.CODENAME;

    final Context mContext;
    final boolean mFactoryTest;
    final boolean mNoDexOpt;
    final DisplayMetrics mMetrics;
    final int mDefParseFlags;
    final String[] mSeparateProcesses;

    // This is where all application persistent data goes.
    final File mAppDataDir;

    // This is the object monitoring the framework dir.
    final FileObserver mFrameworkInstallObserver;

    // This is the object monitoring the system app dir.
    final FileObserver mSystemInstallObserver;

    // This is the object monitoring mAppInstallDir.
    final FileObserver mAppInstallObserver;

    // This is the object monitoring mDrmAppPrivateInstallDir.
    final FileObserver mDrmAppInstallObserver;

    // Used for priviledge escalation.  MUST NOT BE CALLED WITH mPackages
    // LOCK HELD.  Can be called with mInstallLock held.
    final Installer mInstaller;

    final File mFrameworkDir;
    final File mSystemAppDir;
    final File mAppInstallDir;
    final File mDalvikCacheDir;

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
    File mScanningPath;
    int mLastScanError;

    final int[] mOutPermissions = new int[3];

    // ----------------------------------------------------------------

    // Keys are String (package name), values are Package.  This also serves
    // as the lock for the global state.  Methods that must be called with
    // this lock held have the prefix "LP".
    final HashMap<String, PackageParser.Package> mPackages =
            new HashMap<String, PackageParser.Package>();

    final Settings mSettings;
    boolean mRestoredSettings;

    // Group-ids that are given to all packages as read from etc/permissions/*.xml.
    int[] mGlobalGids;

    // These are the built-in uid -> permission mappings that were read from the
    // etc/permissions.xml file.
    final SparseArray<HashSet<String>> mSystemPermissions =
            new SparseArray<HashSet<String>>();

    // These are the built-in shared libraries that were read from the
    // etc/permissions.xml file.
    final HashMap<String, String> mSharedLibraries = new HashMap<String, String>();

    // Temporary for building the final shared libraries for an .apk.
    String[] mTmpSharedLibraries = null;

    // These are the features this devices supports that were read from the
    // etc/permissions.xml file.
    final HashMap<String, FeatureInfo> mAvailableFeatures =
            new HashMap<String, FeatureInfo>();

    // All available activities, for your resolving pleasure.
    final ActivityIntentResolver mActivities =
            new ActivityIntentResolver();

    // All available receivers, for your resolving pleasure.
    final ActivityIntentResolver mReceivers =
            new ActivityIntentResolver();

    // All available services, for your resolving pleasure.
    final ServiceIntentResolver mServices = new ServiceIntentResolver();

    // Keys are String (provider class name), values are Provider.
    final HashMap<ComponentName, PackageParser.Provider> mProvidersByComponent =
            new HashMap<ComponentName, PackageParser.Provider>();

    // Mapping from provider base names (first directory in content URI codePath)
    // to the provider information.
    final HashMap<String, PackageParser.Provider> mProviders =
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

    boolean mSystemReady;
    boolean mSafeMode;
    boolean mHasSystemUidErrors;

    ApplicationInfo mAndroidApplication;
    final ActivityInfo mResolveActivity = new ActivityInfo();
    final ResolveInfo mResolveInfo = new ResolveInfo();
    ComponentName mResolveComponentName;
    PackageParser.Package mPlatformPackage;

    // Set of pending broadcasts for aggregating enable/disable of components.
    final HashMap<String, ArrayList<String>> mPendingBroadcasts
            = new HashMap<String, ArrayList<String>>();
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

    static final int WRITE_SETTINGS_DELAY = 10*1000;  // 10 seconds

    // Delay time in millisecs
    static final int BROADCAST_DELAY = 10 * 1000;
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

    class PackageHandler extends Handler {
        private boolean mBound = false;
        final ArrayList<HandlerParams> mPendingInstalls =
            new ArrayList<HandlerParams>();

        private boolean connectToService() {
            if (DEBUG_SD_INSTALL) Log.i(TAG, "Trying to bind to" +
                    " DefaultContainerService");
            Intent service = new Intent().setComponent(DEFAULT_CONTAINER_COMPONENT);
            Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
            if (mContext.bindService(service, mDefContainerConn,
                    Context.BIND_AUTO_CREATE)) {
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
                    if (DEBUG_SD_INSTALL) Log.i(TAG, "init_copy");
                    HandlerParams params = (HandlerParams) msg.obj;
                    int idx = mPendingInstalls.size();
                    if (DEBUG_SD_INSTALL) Log.i(TAG, "idx=" + idx);
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
                    if (DEBUG_SD_INSTALL) Log.i(TAG, "mcs_bound");
                    if (msg.obj != null) {
                        mContainerService = (IMediaContainerService) msg.obj;
                    }
                    if (mContainerService == null) {
                        // Something seriously wrong. Bail out
                        Slog.e(TAG, "Cannot bind to media container service");
                        for (HandlerParams params : mPendingInstalls) {
                            mPendingInstalls.remove(0);
                            // Indicate service bind error
                            params.serviceError();
                        }
                        mPendingInstalls.clear();
                    } else if (mPendingInstalls.size() > 0) {
                        HandlerParams params = mPendingInstalls.get(0);
                        if (params != null) {
                            params.startCopy();
                        }
                    } else {
                        // Should never happen ideally.
                        Slog.w(TAG, "Empty queue");
                    }
                    break;
                }
                case MCS_RECONNECT : {
                    if (DEBUG_SD_INSTALL) Log.i(TAG, "mcs_reconnect");
                    if (mPendingInstalls.size() > 0) {
                        if (mBound) {
                            disconnectService();
                        }
                        if (!connectToService()) {
                            Slog.e(TAG, "Failed to bind to media container service");
                            for (HandlerParams params : mPendingInstalls) {
                                mPendingInstalls.remove(0);
                                // Indicate service bind error
                                params.serviceError();
                            }
                            mPendingInstalls.clear();
                        }
                    }
                    break;
                }
                case MCS_UNBIND : {
                    if (DEBUG_SD_INSTALL) Log.i(TAG, "mcs_unbind");
                    // Delete pending install
                    if (mPendingInstalls.size() > 0) {
                        mPendingInstalls.remove(0);
                    }
                    if (mPendingInstalls.size() == 0) {
                        if (mBound) {
                            disconnectService();
                        }
                    } else {
                        // There are more pending requests in queue.
                        // Just post MCS_BOUND message to trigger processing
                        // of next pending install.
                        mHandler.sendEmptyMessage(MCS_BOUND);
                    }
                    break;
                }
                case MCS_GIVE_UP: {
                    if (DEBUG_SD_INSTALL) Log.i(TAG, "mcs_giveup too many retries");
                    HandlerParams params = mPendingInstalls.remove(0);
                    break;
                }
                case SEND_PENDING_BROADCAST : {
                    String packages[];
                    ArrayList components[];
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
                        Iterator<HashMap.Entry<String, ArrayList<String>>>
                                it = mPendingBroadcasts.entrySet().iterator();
                        int i = 0;
                        while (it.hasNext() && i < size) {
                            HashMap.Entry<String, ArrayList<String>> ent = it.next();
                            packages[i] = ent.getKey();
                            components[i] = ent.getValue();
                            PackageSetting ps = mSettings.mPackages.get(ent.getKey());
                            uids[i] = (ps != null) ? ps.userId : -1;
                            i++;
                        }
                        size = i;
                        mPendingBroadcasts.clear();
                    }
                    // Send broadcasts
                    for (int i = 0; i < size; i++) {
                        sendPackageChangedBroadcast(packages[i], true,
                                (ArrayList<String>)components[i], uids[i]);
                    }
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                    break;
                }
                case START_CLEANING_PACKAGE: {
                    String packageName = (String)msg.obj;
                    Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
                    synchronized (mPackages) {
                        if (!mSettings.mPackagesToBeCleaned.contains(packageName)) {
                            mSettings.mPackagesToBeCleaned.add(packageName);
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
                            res.removedInfo.sendBroadcast(false, true);
                            Bundle extras = new Bundle(1);
                            extras.putInt(Intent.EXTRA_UID, res.uid);
                            final boolean update = res.removedInfo.removedPackage != null;
                            if (update) {
                                extras.putBoolean(Intent.EXTRA_REPLACING, true);
                            }
                            sendPackageBroadcast(Intent.ACTION_PACKAGE_ADDED,
                                    res.pkg.applicationInfo.packageName,
                                    extras, null);
                            if (update) {
                                sendPackageBroadcast(Intent.ACTION_PACKAGE_REPLACED,
                                        res.pkg.applicationInfo.packageName,
                                        extras, null);
                            }
                            if (res.removedInfo.args != null) {
                                // Remove the replaced package's older resources safely now
                                deleteOld = true;
                            }
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
                        Set<SdInstallArgs> args = (Set<SdInstallArgs>) msg.obj;
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
                        mSettings.writeLP();
                    }
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                } break;
            }
        }
    }

    void scheduleWriteSettingsLocked() {
        if (!mHandler.hasMessages(WRITE_SETTINGS)) {
            mHandler.sendEmptyMessageDelayed(WRITE_SETTINGS, WRITE_SETTINGS_DELAY);
        }
    }
    
    static boolean installOnSd(int flags) {
        if (((flags & PackageManager.INSTALL_FORWARD_LOCK) != 0) ||
                ((flags & PackageManager.INSTALL_INTERNAL) != 0)) {
            return false;
        }
        if ((flags & PackageManager.INSTALL_EXTERNAL) != 0) {
            return true;
        }
        return false;
    }

    static boolean isFwdLocked(int flags) {
        if ((flags & PackageManager.INSTALL_FORWARD_LOCK) != 0) {
            return true;
        }
        return false;
    }

    public static final IPackageManager main(Context context, boolean factoryTest) {
        PackageManagerService m = new PackageManagerService(context, factoryTest);
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

    public PackageManagerService(Context context, boolean factoryTest) {
        EventLog.writeEvent(EventLogTags.BOOT_PROGRESS_PMS_START,
                SystemClock.uptimeMillis());

        if (mSdkVersion <= 0) {
            Slog.w(TAG, "**** ro.build.version.sdk not set!");
        }

        mContext = context;
        mFactoryTest = factoryTest;
        mNoDexOpt = "eng".equals(SystemProperties.get("ro.build.type"));
        mMetrics = new DisplayMetrics();
        mSettings = new Settings();
        mSettings.addSharedUserLP("android.uid.system",
                Process.SYSTEM_UID, ApplicationInfo.FLAG_SYSTEM);
        mSettings.addSharedUserLP("android.uid.phone",
                MULTIPLE_APPLICATION_UIDS
                        ? RADIO_UID : FIRST_APPLICATION_UID,
                ApplicationInfo.FLAG_SYSTEM);
        mSettings.addSharedUserLP("android.uid.log",
                MULTIPLE_APPLICATION_UIDS
                        ? LOG_UID : FIRST_APPLICATION_UID,
                ApplicationInfo.FLAG_SYSTEM);

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

        Installer installer = new Installer();
        // Little hacky thing to check if installd is here, to determine
        // whether we are running on the simulator and thus need to take
        // care of building the /data file structure ourself.
        // (apparently the sim now has a working installer)
        if (installer.ping() && Process.supportsProcesses()) {
            mInstaller = installer;
        } else {
            mInstaller = null;
        }

        WindowManager wm = (WindowManager)context.getSystemService(Context.WINDOW_SERVICE);
        Display d = wm.getDefaultDisplay();
        d.getMetrics(mMetrics);

        synchronized (mInstallLock) {
        synchronized (mPackages) {
            mHandlerThread.start();
            mHandler = new PackageHandler(mHandlerThread.getLooper());

            File dataDir = Environment.getDataDirectory();
            mAppDataDir = new File(dataDir, "data");
            mDrmAppPrivateInstallDir = new File(dataDir, "app-private");

            if (mInstaller == null) {
                // Make sure these dirs exist, when we are running in
                // the simulator.
                // Make a wide-open directory for random misc stuff.
                File miscDir = new File(dataDir, "misc");
                miscDir.mkdirs();
                mAppDataDir.mkdirs();
                mDrmAppPrivateInstallDir.mkdirs();
            }

            readPermissions();

            mRestoredSettings = mSettings.readLP();
            long startTime = SystemClock.uptimeMillis();

            EventLog.writeEvent(EventLogTags.BOOT_PROGRESS_PMS_SYSTEM_SCAN_START,
                    startTime);

            // Set flag to monitor and not change apk file paths when
            // scanning install directories.
            int scanMode = SCAN_MONITOR | SCAN_NO_PATHS;
            if (mNoDexOpt) {
                Slog.w(TAG, "Running ENG build: no pre-dexopt!");
                scanMode |= SCAN_NO_DEX;
            }

            final HashSet<String> libFiles = new HashSet<String>();

            mFrameworkDir = new File(Environment.getRootDirectory(), "framework");
            mDalvikCacheDir = new File(dataDir, "dalvik-cache");

            if (mInstaller != null) {
                boolean didDexOpt = false;

                /**
                 * Out of paranoia, ensure that everything in the boot class
                 * path has been dexed.
                 */
                String bootClassPath = System.getProperty("java.boot.class.path");
                if (bootClassPath != null) {
                    String[] paths = splitString(bootClassPath, ':');
                    for (int i=0; i<paths.length; i++) {
                        try {
                            if (dalvik.system.DexFile.isDexOptNeeded(paths[i])) {
                                libFiles.add(paths[i]);
                                mInstaller.dexopt(paths[i], Process.SYSTEM_UID, true);
                                didDexOpt = true;
                            }
                        } catch (FileNotFoundException e) {
                            Slog.w(TAG, "Boot class path not found: " + paths[i]);
                        } catch (IOException e) {
                            Slog.w(TAG, "Exception reading boot class path: " + paths[i], e);
                        }
                    }
                } else {
                    Slog.w(TAG, "No BOOTCLASSPATH found!");
                }

                /**
                 * Also ensure all external libraries have had dexopt run on them.
                 */
                if (mSharedLibraries.size() > 0) {
                    Iterator<String> libs = mSharedLibraries.values().iterator();
                    while (libs.hasNext()) {
                        String lib = libs.next();
                        try {
                            if (dalvik.system.DexFile.isDexOptNeeded(lib)) {
                                libFiles.add(lib);
                                mInstaller.dexopt(lib, Process.SYSTEM_UID, true);
                                didDexOpt = true;
                            }
                        } catch (FileNotFoundException e) {
                            Slog.w(TAG, "Library not found: " + lib);
                        } catch (IOException e) {
                            Slog.w(TAG, "Exception reading library: " + lib, e);
                        }
                    }
                }

                // Gross hack for now: we know this file doesn't contain any
                // code, so don't dexopt it to avoid the resulting log spew.
                libFiles.add(mFrameworkDir.getPath() + "/framework-res.apk");

                /**
                 * And there are a number of commands implemented in Java, which
                 * we currently need to do the dexopt on so that they can be
                 * run from a non-root shell.
                 */
                String[] frameworkFiles = mFrameworkDir.list();
                if (frameworkFiles != null) {
                    for (int i=0; i<frameworkFiles.length; i++) {
                        File libPath = new File(mFrameworkDir, frameworkFiles[i]);
                        String path = libPath.getPath();
                        // Skip the file if we alrady did it.
                        if (libFiles.contains(path)) {
                            continue;
                        }
                        // Skip the file if it is not a type we want to dexopt.
                        if (!path.endsWith(".apk") && !path.endsWith(".jar")) {
                            continue;
                        }
                        try {
                            if (dalvik.system.DexFile.isDexOptNeeded(path)) {
                                mInstaller.dexopt(path, Process.SYSTEM_UID, true);
                                didDexOpt = true;
                            }
                        } catch (FileNotFoundException e) {
                            Slog.w(TAG, "Jar not found: " + path);
                        } catch (IOException e) {
                            Slog.w(TAG, "Exception reading jar: " + path, e);
                        }
                    }
                }

                if (didDexOpt) {
                    // If we had to do a dexopt of one of the previous
                    // things, then something on the system has changed.
                    // Consider this significant, and wipe away all other
                    // existing dexopt files to ensure we don't leave any
                    // dangling around.
                    String[] files = mDalvikCacheDir.list();
                    if (files != null) {
                        for (int i=0; i<files.length; i++) {
                            String fn = files[i];
                            if (fn.startsWith("data@app@")
                                    || fn.startsWith("data@app-private@")) {
                                Slog.i(TAG, "Pruning dalvik file: " + fn);
                                (new File(mDalvikCacheDir, fn)).delete();
                            }
                        }
                    }
                }
            }

            // Find base frameworks (resource packages without code).
            mFrameworkInstallObserver = new AppDirObserver(
                mFrameworkDir.getPath(), OBSERVER_EVENTS, true);
            mFrameworkInstallObserver.startWatching();
            scanDirLI(mFrameworkDir, PackageParser.PARSE_IS_SYSTEM
                    | PackageParser.PARSE_IS_SYSTEM_DIR,
                    scanMode | SCAN_NO_DEX);
            
            // Collect all system packages.
            mSystemAppDir = new File(Environment.getRootDirectory(), "app");
            mSystemInstallObserver = new AppDirObserver(
                mSystemAppDir.getPath(), OBSERVER_EVENTS, true);
            mSystemInstallObserver.startWatching();
            scanDirLI(mSystemAppDir, PackageParser.PARSE_IS_SYSTEM
                    | PackageParser.PARSE_IS_SYSTEM_DIR, scanMode);
            
            if (mInstaller != null) {
                if (DEBUG_UPGRADE) Log.v(TAG, "Running installd update commands");
                mInstaller.moveFiles();
            }
            
            // Prune any system packages that no longer exist.
            Iterator<PackageSetting> psit = mSettings.mPackages.values().iterator();
            while (psit.hasNext()) {
                PackageSetting ps = psit.next();
                if ((ps.pkgFlags&ApplicationInfo.FLAG_SYSTEM) != 0
                        && !mPackages.containsKey(ps.name)
                        && !mSettings.mDisabledSysPackages.containsKey(ps.name)) {
                    psit.remove();
                    String msg = "System package " + ps.name
                            + " no longer exists; wiping its data";
                    reportSettingsProblem(Log.WARN, msg);
                    if (mInstaller != null) {
                        mInstaller.remove(ps.name);
                    }
                }
            }
            
            mAppInstallDir = new File(dataDir, "app");
            if (mInstaller == null) {
                // Make sure these dirs exist, when we are running in
                // the simulator.
                mAppInstallDir.mkdirs(); // scanDirLI() assumes this dir exists
            }
            //look for any incomplete package installations
            ArrayList<PackageSetting> deletePkgsList = mSettings.getListOfIncompleteInstallPackages();
            //clean up list
            for(int i = 0; i < deletePkgsList.size(); i++) {
                //clean up here
                cleanupInstallFailedPackage(deletePkgsList.get(i));
            }
            //delete tmp files
            deleteTempPackageFiles();

            EventLog.writeEvent(EventLogTags.BOOT_PROGRESS_PMS_DATA_SCAN_START,
                    SystemClock.uptimeMillis());
            mAppInstallObserver = new AppDirObserver(
                mAppInstallDir.getPath(), OBSERVER_EVENTS, false);
            mAppInstallObserver.startWatching();
            scanDirLI(mAppInstallDir, 0, scanMode);

            mDrmAppInstallObserver = new AppDirObserver(
                mDrmAppPrivateInstallDir.getPath(), OBSERVER_EVENTS, false);
            mDrmAppInstallObserver.startWatching();
            scanDirLI(mDrmAppPrivateInstallDir, PackageParser.PARSE_FORWARD_LOCK, scanMode);

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
            
            updatePermissionsLP(null, null, true, regrantPermissions, regrantPermissions);

            mSettings.writeLP();

            EventLog.writeEvent(EventLogTags.BOOT_PROGRESS_PMS_READY,
                    SystemClock.uptimeMillis());

            // Now after opening every single application zip, make sure they
            // are all flushed.  Not really needed, but keeps things nice and
            // tidy.
            Runtime.getRuntime().gc();
        } // synchronized (mPackages)
        } // synchronized (mInstallLock)
    }

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        try {
            return super.onTransact(code, data, reply, flags);
        } catch (RuntimeException e) {
            if (!(e instanceof SecurityException) && !(e instanceof IllegalArgumentException)) {
                Slog.e(TAG, "Package Manager Crash", e);
            }
            throw e;
        }
    }

    void cleanupInstallFailedPackage(PackageSetting ps) {
        Slog.i(TAG, "Cleaning up incompletely installed app: " + ps.name);
        if (mInstaller != null) {
            int retCode = mInstaller.remove(ps.name);
            if (retCode < 0) {
                Slog.w(TAG, "Couldn't remove app data directory for package: "
                           + ps.name + ", retcode=" + retCode);
            }
        } else {
            //for emulator
            PackageParser.Package pkg = mPackages.get(ps.name);
            File dataDir = new File(pkg.applicationInfo.dataDir);
            dataDir.delete();
        }
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
        mSettings.removePackageLP(ps.name);
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

        StringBuilder sb = new StringBuilder(128);
        sb.append("Libs:");
        Iterator<String> it = mSharedLibraries.keySet().iterator();
        while (it.hasNext()) {
            sb.append(' ');
            String name = it.next();
            sb.append(name);
            sb.append(':');
            sb.append(mSharedLibraries.get(name));
        }
        Log.i(TAG, sb.toString());

        sb.setLength(0);
        sb.append("Features:");
        it = mAvailableFeatures.keySet().iterator();
        while (it.hasNext()) {
            sb.append(' ');
            sb.append(it.next());
        }
        Log.i(TAG, sb.toString());
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
                        int gid = Integer.parseInt(gidStr);
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
                        mSharedLibraries.put(lname, lfile);
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

    static int[] appendInt(int[] cur, int val) {
        if (cur == null) {
            return new int[] { val };
        }
        final int N = cur.length;
        for (int i=0; i<N; i++) {
            if (cur[i] == val) {
                return cur;
            }
        }
        int[] ret = new int[N+1];
        System.arraycopy(cur, 0, ret, 0, N);
        ret[N] = val;
        return ret;
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

    static int[] removeInt(int[] cur, int val) {
        if (cur == null) {
            return null;
        }
        final int N = cur.length;
        for (int i=0; i<N; i++) {
            if (cur[i] == val) {
                int[] ret = new int[N-1];
                if (i > 0) {
                    System.arraycopy(cur, 0, ret, 0, i);
                }
                if (i < (N-1)) {
                    System.arraycopy(cur, i + 1, ret, i, N - i - 1);
                }
                return ret;
            }
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

    PackageInfo generatePackageInfo(PackageParser.Package p, int flags) {
        if ((flags & PackageManager.GET_UNINSTALLED_PACKAGES) != 0) {
            // The package has been uninstalled but has retained data and resources.
            return PackageParser.generatePackageInfo(p, null, flags);
        }
        final PackageSetting ps = (PackageSetting)p.mExtras;
        if (ps == null) {
            return null;
        }
        final GrantedPermissions gp = ps.sharedUser != null ? ps.sharedUser : ps;
        return PackageParser.generatePackageInfo(p, gp.gids, flags);
    }

    public PackageInfo getPackageInfo(String packageName, int flags) {
        synchronized (mPackages) {
            PackageParser.Package p = mPackages.get(packageName);
            if (Config.LOGV) Log.v(
                TAG, "getPackageInfo " + packageName
                + ": " + p);
            if (p != null) {
                return generatePackageInfo(p, flags);
            }
            if((flags & PackageManager.GET_UNINSTALLED_PACKAGES) != 0) {
                return generatePackageInfoFromSettingsLP(packageName, flags);
            }
        }
        return null;
    }

    public String[] currentToCanonicalPackageNames(String[] names) {
        String[] out = new String[names.length];
        synchronized (mPackages) {
            for (int i=names.length-1; i>=0; i--) {
                PackageSetting ps = mSettings.mPackages.get(names[i]);
                out[i] = ps != null && ps.realName != null ? ps.realName : names[i];
            }
        }
        return out;
    }
    
    public String[] canonicalToCurrentPackageNames(String[] names) {
        String[] out = new String[names.length];
        synchronized (mPackages) {
            for (int i=names.length-1; i>=0; i--) {
                String cur = mSettings.mRenamedPackages.get(names[i]);
                out[i] = cur != null ? cur : names[i];
            }
        }
        return out;
    }
    
    public int getPackageUid(String packageName) {
        synchronized (mPackages) {
            PackageParser.Package p = mPackages.get(packageName);
            if(p != null) {
                return p.applicationInfo.uid;
            }
            PackageSetting ps = mSettings.mPackages.get(packageName);
            if((ps == null) || (ps.pkg == null) || (ps.pkg.applicationInfo == null)) {
                return -1;
            }
            p = ps.pkg;
            return p != null ? p.applicationInfo.uid : -1;
        }
    }

    public int[] getPackageGids(String packageName) {
        synchronized (mPackages) {
            PackageParser.Package p = mPackages.get(packageName);
            if (Config.LOGV) Log.v(
                TAG, "getPackageGids" + packageName
                + ": " + p);
            if (p != null) {
                final PackageSetting ps = (PackageSetting)p.mExtras;
                final SharedUserSetting suid = ps.sharedUser;
                return suid != null ? suid.gids : ps.gids;
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
    
    public PermissionInfo getPermissionInfo(String name, int flags) {
        synchronized (mPackages) {
            final BasePermission p = mSettings.mPermissions.get(name);
            if (p != null) {
                return generatePermissionInfo(p, flags);
            }
            return null;
        }
    }

    public List<PermissionInfo> queryPermissionsByGroup(String group, int flags) {
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

    public PermissionGroupInfo getPermissionGroupInfo(String name, int flags) {
        synchronized (mPackages) {
            return PackageParser.generatePermissionGroupInfo(
                    mPermissionGroups.get(name), flags);
        }
    }

    public List<PermissionGroupInfo> getAllPermissionGroups(int flags) {
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

    private ApplicationInfo generateApplicationInfoFromSettingsLP(String packageName, int flags) {
        PackageSetting ps = mSettings.mPackages.get(packageName);
        if(ps != null) {
            if(ps.pkg == null) {
                PackageInfo pInfo = generatePackageInfoFromSettingsLP(packageName, flags);
                if(pInfo != null) {
                    return pInfo.applicationInfo;
                }
                return null;
            }
            return PackageParser.generateApplicationInfo(ps.pkg, flags);
        }
        return null;
    }

    private PackageInfo generatePackageInfoFromSettingsLP(String packageName, int flags) {
        PackageSetting ps = mSettings.mPackages.get(packageName);
        if(ps != null) {
            if(ps.pkg == null) {
                ps.pkg = new PackageParser.Package(packageName);
                ps.pkg.applicationInfo.packageName = packageName;
                ps.pkg.applicationInfo.flags = ps.pkgFlags;
                ps.pkg.applicationInfo.publicSourceDir = ps.resourcePathString;
                ps.pkg.applicationInfo.sourceDir = ps.codePathString;
                ps.pkg.applicationInfo.dataDir = getDataPathForPackage(ps.pkg).getPath();
            }
            return generatePackageInfo(ps.pkg, flags);
        }
        return null;
    }

    public ApplicationInfo getApplicationInfo(String packageName, int flags) {
        synchronized (mPackages) {
            PackageParser.Package p = mPackages.get(packageName);
            if (Config.LOGV) Log.v(
                    TAG, "getApplicationInfo " + packageName
                    + ": " + p);
            if (p != null) {
                // Note: isEnabledLP() does not apply here - always return info
                return PackageParser.generateApplicationInfo(p, flags);
            }
            if ("android".equals(packageName)||"system".equals(packageName)) {
                return mAndroidApplication;
            }
            if((flags & PackageManager.GET_UNINSTALLED_PACKAGES) != 0) {
                return generateApplicationInfoFromSettingsLP(packageName, flags);
            }
        }
        return null;
    }


    public void freeStorageAndNotify(final long freeStorageSize, final IPackageDataObserver observer) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CLEAR_APP_CACHE, null);
        // Queue up an async operation since clearing cache may take a little while.
        mHandler.post(new Runnable() {
            public void run() {
                mHandler.removeCallbacks(this);
                int retCode = -1;
                if (mInstaller != null) {
                    retCode = mInstaller.freeCache(freeStorageSize);
                    if (retCode < 0) {
                        Slog.w(TAG, "Couldn't clear application caches");
                    }
                } //end if mInstaller
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

    public void freeStorage(final long freeStorageSize, final IntentSender pi) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CLEAR_APP_CACHE, null);
        // Queue up an async operation since clearing cache may take a little while.
        mHandler.post(new Runnable() {
            public void run() {
                mHandler.removeCallbacks(this);
                int retCode = -1;
                if (mInstaller != null) {
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

    public ActivityInfo getActivityInfo(ComponentName component, int flags) {
        synchronized (mPackages) {
            PackageParser.Activity a = mActivities.mActivities.get(component);

            if (Config.LOGV) Log.v(TAG, "getActivityInfo " + component + ": " + a);
            if (a != null && mSettings.isEnabledLP(a.info, flags)) {
                return PackageParser.generateActivityInfo(a, flags);
            }
            if (mResolveComponentName.equals(component)) {
                return mResolveActivity;
            }
        }
        return null;
    }

    public ActivityInfo getReceiverInfo(ComponentName component, int flags) {
        synchronized (mPackages) {
            PackageParser.Activity a = mReceivers.mActivities.get(component);
            if (Config.LOGV) Log.v(
                TAG, "getReceiverInfo " + component + ": " + a);
            if (a != null && mSettings.isEnabledLP(a.info, flags)) {
                return PackageParser.generateActivityInfo(a, flags);
            }
        }
        return null;
    }

    public ServiceInfo getServiceInfo(ComponentName component, int flags) {
        synchronized (mPackages) {
            PackageParser.Service s = mServices.mServices.get(component);
            if (Config.LOGV) Log.v(
                TAG, "getServiceInfo " + component + ": " + s);
            if (s != null && mSettings.isEnabledLP(s.info, flags)) {
                return PackageParser.generateServiceInfo(s, flags);
            }
        }
        return null;
    }

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

    public boolean hasSystemFeature(String name) {
        synchronized (mPackages) {
            return mAvailableFeatures.containsKey(name);
        }
    }

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

    public int checkUidPermission(String permName, int uid) {
        synchronized (mPackages) {
            Object obj = mSettings.getUserIdLP(uid);
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
                if (bp.uid == Binder.getCallingUid()) {
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
        if (added) {
            bp = new BasePermission(info.name, tree.sourcePackage,
                    BasePermission.TYPE_DYNAMIC);
        } else if (bp.type != BasePermission.TYPE_DYNAMIC) {
            throw new SecurityException(
                    "Not allowed to modify non-dynamic permission "
                    + info.name);
        } else {
            if (bp.protectionLevel == info.protectionLevel
                    && bp.perm.owner.equals(tree.perm.owner)
                    && bp.uid == tree.uid
                    && comparePermissionInfos(bp.perm.info, info)) {
                changed = false;
            }
        }
        bp.protectionLevel = info.protectionLevel;
        bp.perm = new PackageParser.Permission(tree.perm.owner,
                new PermissionInfo(info));
        bp.perm.info.packageName = tree.perm.info.packageName;
        bp.uid = tree.uid;
        if (added) {
            mSettings.mPermissions.put(info.name, bp);
        }
        if (changed) {
            if (!async) {
                mSettings.writeLP();
            } else {
                scheduleWriteSettingsLocked();            
            }
        }
        return added;
    }

    public boolean addPermission(PermissionInfo info) {
        synchronized (mPackages) {
            return addPermissionLocked(info, false);
        }
    }

    public boolean addPermissionAsync(PermissionInfo info) {
        synchronized (mPackages) {
            return addPermissionLocked(info, true);
        }
    }

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
                mSettings.writeLP();
            }
        }
    }

    public boolean isProtectedBroadcast(String actionName) {
        synchronized (mPackages) {
            return mProtectedBroadcasts.contains(actionName);
        }
    }

    public int checkSignatures(String pkg1, String pkg2) {
        synchronized (mPackages) {
            PackageParser.Package p1 = mPackages.get(pkg1);
            PackageParser.Package p2 = mPackages.get(pkg2);
            if (p1 == null || p1.mExtras == null
                    || p2 == null || p2.mExtras == null) {
                return PackageManager.SIGNATURE_UNKNOWN_PACKAGE;
            }
            return checkSignaturesLP(p1.mSignatures, p2.mSignatures);
        }
    }

    public int checkUidSignatures(int uid1, int uid2) {
        synchronized (mPackages) {
            Signature[] s1;
            Signature[] s2;
            Object obj = mSettings.getUserIdLP(uid1);
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
            obj = mSettings.getUserIdLP(uid2);
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
            return checkSignaturesLP(s1, s2);
        }
    }

    int checkSignaturesLP(Signature[] s1, Signature[] s2) {
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

    public String[] getPackagesForUid(int uid) {
        synchronized (mPackages) {
            Object obj = mSettings.getUserIdLP(uid);
            if (obj instanceof SharedUserSetting) {
                SharedUserSetting sus = (SharedUserSetting)obj;
                final int N = sus.packages.size();
                String[] res = new String[N];
                Iterator<PackageSetting> it = sus.packages.iterator();
                int i=0;
                while (it.hasNext()) {
                    res[i++] = it.next().name;
                }
                return res;
            } else if (obj instanceof PackageSetting) {
                PackageSetting ps = (PackageSetting)obj;
                return new String[] { ps.name };
            }
        }
        return null;
    }

    public String getNameForUid(int uid) {
        synchronized (mPackages) {
            Object obj = mSettings.getUserIdLP(uid);
            if (obj instanceof SharedUserSetting) {
                SharedUserSetting sus = (SharedUserSetting)obj;
                return sus.name + ":" + sus.userId;
            } else if (obj instanceof PackageSetting) {
                PackageSetting ps = (PackageSetting)obj;
                return ps.name;
            }
        }
        return null;
    }

    public int getUidForSharedUser(String sharedUserName) {
        if(sharedUserName == null) {
            return -1;
        }
        synchronized (mPackages) {
            SharedUserSetting suid = mSettings.getSharedUserLP(sharedUserName, 0, false);
            if(suid == null) {
                return -1;
            }
            return suid.userId;
        }
    }

    public ResolveInfo resolveIntent(Intent intent, String resolvedType,
            int flags) {
        List<ResolveInfo> query = queryIntentActivities(intent, resolvedType, flags);
        return chooseBestActivity(intent, resolvedType, flags, query);
    }

    private ResolveInfo chooseBestActivity(Intent intent, String resolvedType,
                                           int flags, List<ResolveInfo> query) {
        if (query != null) {
            final int N = query.size();
            if (N == 1) {
                return query.get(0);
            } else if (N > 1) {
                // If there is more than one activity with the same priority,
                // then let the user decide between them.
                ResolveInfo r0 = query.get(0);
                ResolveInfo r1 = query.get(1);
                if (false) {
                    System.out.println(r0.activityInfo.name +
                                       "=" + r0.priority + " vs " +
                                       r1.activityInfo.name +
                                       "=" + r1.priority);
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
                        flags, query, r0.priority);
                if (ri != null) {
                    return ri;
                }
                return mResolveInfo;
            }
        }
        return null;
    }

    ResolveInfo findPreferredActivity(Intent intent, String resolvedType,
            int flags, List<ResolveInfo> query, int priority) {
        synchronized (mPackages) {
            if (DEBUG_PREFERRED) intent.addFlags(Intent.FLAG_DEBUG_LOG_RESOLUTION);
            List<PreferredActivity> prefs =
                    mSettings.mPreferredActivities.queryIntent(intent, resolvedType,
                            (flags&PackageManager.MATCH_DEFAULT_ONLY) != 0);
            if (prefs != null && prefs.size() > 0) {
                // First figure out how good the original match set is.
                // We will only allow preferred activities that came
                // from the same match quality.
                int match = 0;
                final int N = query.size();
                if (DEBUG_PREFERRED) Log.v(TAG, "Figuring out best match...");
                for (int j=0; j<N; j++) {
                    ResolveInfo ri = query.get(j);
                    if (DEBUG_PREFERRED) Log.v(TAG, "Match for " + ri.activityInfo
                            + ": 0x" + Integer.toHexString(match));
                    if (ri.match > match) match = ri.match;
                }
                if (DEBUG_PREFERRED) Log.v(TAG, "Best match: 0x"
                        + Integer.toHexString(match));
                match &= IntentFilter.MATCH_CATEGORY_MASK;
                final int M = prefs.size();
                for (int i=0; i<M; i++) {
                    PreferredActivity pa = prefs.get(i);
                    if (pa.mMatch != match) {
                        continue;
                    }
                    ActivityInfo ai = getActivityInfo(pa.mActivity, flags);
                    if (DEBUG_PREFERRED) {
                        Log.v(TAG, "Got preferred activity:");
                        ai.dump(new LogPrinter(Log.INFO, TAG), "  ");
                    }
                    if (ai != null) {
                        for (int j=0; j<N; j++) {
                            ResolveInfo ri = query.get(j);
                            if (!ri.activityInfo.applicationInfo.packageName
                                    .equals(ai.applicationInfo.packageName)) {
                                continue;
                            }
                            if (!ri.activityInfo.name.equals(ai.name)) {
                                continue;
                            }

                            // Okay we found a previously set preferred app.
                            // If the result set is different from when this
                            // was created, we need to clear it and re-ask the
                            // user their preference.
                            if (!pa.sameSet(query, priority)) {
                                Slog.i(TAG, "Result set changed, dropping preferred activity for "
                                        + intent + " type " + resolvedType);
                                mSettings.mPreferredActivities.removeFilter(pa);
                                return null;
                            }

                            // Yay!
                            return ri;
                        }
                    }
                }
            }
        }
        return null;
    }

    public List<ResolveInfo> queryIntentActivities(Intent intent,
            String resolvedType, int flags) {
        ComponentName comp = intent.getComponent();
        if (comp != null) {
            List<ResolveInfo> list = new ArrayList<ResolveInfo>(1);
            ActivityInfo ai = getActivityInfo(comp, flags);
            if (ai != null) {
                ResolveInfo ri = new ResolveInfo();
                ri.activityInfo = ai;
                list.add(ri);
            }
            return list;
        }

        synchronized (mPackages) {
            String pkgName = intent.getPackage();
            if (pkgName == null) {
                return (List<ResolveInfo>)mActivities.queryIntent(intent,
                        resolvedType, flags);
            }
            PackageParser.Package pkg = mPackages.get(pkgName);
            if (pkg != null) {
                return (List<ResolveInfo>) mActivities.queryIntentForPackage(intent,
                        resolvedType, flags, pkg.activities);
            }
            return null;
        }
    }

    public List<ResolveInfo> queryIntentActivityOptions(ComponentName caller,
            Intent[] specifics, String[] specificTypes, Intent intent,
            String resolvedType, int flags) {
        final String resultsAction = intent.getAction();

        List<ResolveInfo> results = queryIntentActivities(
            intent, resolvedType, flags|PackageManager.GET_RESOLVED_FILTER);
        if (Config.LOGV) Log.v(TAG, "Query " + intent + ": " + results);

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

                if (Config.LOGV) Log.v(TAG, "Specific #" + i + ": " + sintent);
                String action = sintent.getAction();
                if (resultsAction != null && resultsAction.equals(action)) {
                    // If this action was explicitly requested, then don't
                    // remove things that have it.
                    action = null;
                }
                ComponentName comp = sintent.getComponent();
                ResolveInfo ri = null;
                ActivityInfo ai = null;
                if (comp == null) {
                    ri = resolveIntent(
                        sintent,
                        specificTypes != null ? specificTypes[i] : null,
                        flags);
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
                    ai = getActivityInfo(comp, flags);
                    if (ai == null) {
                        continue;
                    }
                }

                // Look for any generic query activities that are duplicates
                // of this specific one, and remove them from the results.
                if (Config.LOGV) Log.v(TAG, "Specific #" + i + ": " + ai);
                N = results.size();
                int j;
                for (j=specificsPos; j<N; j++) {
                    ResolveInfo sri = results.get(j);
                    if ((sri.activityInfo.name.equals(comp.getClassName())
                            && sri.activityInfo.applicationInfo.packageName.equals(
                                    comp.getPackageName()))
                        || (action != null && sri.filter.matchAction(action))) {
                        results.remove(j);
                        if (Config.LOGV) Log.v(
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
                        if (Config.LOGV) Log.v(
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

        if (Config.LOGV) Log.v(TAG, "Result: " + results);
        return results;
    }

    public List<ResolveInfo> queryIntentReceivers(Intent intent,
            String resolvedType, int flags) {
        ComponentName comp = intent.getComponent();
        if (comp != null) {
            List<ResolveInfo> list = new ArrayList<ResolveInfo>(1);
            ActivityInfo ai = getReceiverInfo(comp, flags);
            if (ai != null) {
                ResolveInfo ri = new ResolveInfo();
                ri.activityInfo = ai;
                list.add(ri);
            }
            return list;
        }

        synchronized (mPackages) {
            String pkgName = intent.getPackage();
            if (pkgName == null) {
                return (List<ResolveInfo>)mReceivers.queryIntent(intent,
                        resolvedType, flags);
            }
            PackageParser.Package pkg = mPackages.get(pkgName);
            if (pkg != null) {
                return (List<ResolveInfo>) mReceivers.queryIntentForPackage(intent,
                        resolvedType, flags, pkg.receivers);
            }
            return null;
        }
    }

    public ResolveInfo resolveService(Intent intent, String resolvedType,
            int flags) {
        List<ResolveInfo> query = queryIntentServices(intent, resolvedType,
                flags);
        if (query != null) {
            if (query.size() >= 1) {
                // If there is more than one service with the same priority,
                // just arbitrarily pick the first one.
                return query.get(0);
            }
        }
        return null;
    }

    public List<ResolveInfo> queryIntentServices(Intent intent,
            String resolvedType, int flags) {
        ComponentName comp = intent.getComponent();
        if (comp != null) {
            List<ResolveInfo> list = new ArrayList<ResolveInfo>(1);
            ServiceInfo si = getServiceInfo(comp, flags);
            if (si != null) {
                ResolveInfo ri = new ResolveInfo();
                ri.serviceInfo = si;
                list.add(ri);
            }
            return list;
        }

        synchronized (mPackages) {
            String pkgName = intent.getPackage();
            if (pkgName == null) {
                return (List<ResolveInfo>)mServices.queryIntent(intent,
                        resolvedType, flags);
            }
            PackageParser.Package pkg = mPackages.get(pkgName);
            if (pkg != null) {
                return (List<ResolveInfo>)mServices.queryIntentForPackage(intent,
                        resolvedType, flags, pkg.services);
            }
            return null;
        }
    }

    public List<PackageInfo> getInstalledPackages(int flags) {
        ArrayList<PackageInfo> finalList = new ArrayList<PackageInfo>();

        synchronized (mPackages) {
            if((flags & PackageManager.GET_UNINSTALLED_PACKAGES) != 0) {
                Iterator<PackageSetting> i = mSettings.mPackages.values().iterator();
                while (i.hasNext()) {
                    final PackageSetting ps = i.next();
                    PackageInfo psPkg = generatePackageInfoFromSettingsLP(ps.name, flags);
                    if(psPkg != null) {
                        finalList.add(psPkg);
                    }
                }
            }
            else {
                Iterator<PackageParser.Package> i = mPackages.values().iterator();
                while (i.hasNext()) {
                    final PackageParser.Package p = i.next();
                    if (p.applicationInfo != null) {
                        PackageInfo pi = generatePackageInfo(p, flags);
                        if(pi != null) {
                            finalList.add(pi);
                        }
                    }
                }
            }
        }
        return finalList;
    }

    public List<ApplicationInfo> getInstalledApplications(int flags) {
        ArrayList<ApplicationInfo> finalList = new ArrayList<ApplicationInfo>();
        synchronized(mPackages) {
            if((flags & PackageManager.GET_UNINSTALLED_PACKAGES) != 0) {
                Iterator<PackageSetting> i = mSettings.mPackages.values().iterator();
                while (i.hasNext()) {
                    final PackageSetting ps = i.next();
                    ApplicationInfo ai = generateApplicationInfoFromSettingsLP(ps.name, flags);
                    if(ai != null) {
                        finalList.add(ai);
                    }
                }
            }
            else {
                Iterator<PackageParser.Package> i = mPackages.values().iterator();
                while (i.hasNext()) {
                    final PackageParser.Package p = i.next();
                    if (p.applicationInfo != null) {
                        ApplicationInfo ai = PackageParser.generateApplicationInfo(p, flags);
                        if(ai != null) {
                            finalList.add(ai);
                        }
                    }
                }
            }
        }
        return finalList;
    }

    public List<ApplicationInfo> getPersistentApplications(int flags) {
        ArrayList<ApplicationInfo> finalList = new ArrayList<ApplicationInfo>();

        synchronized (mPackages) {
            Iterator<PackageParser.Package> i = mPackages.values().iterator();
            while (i.hasNext()) {
                PackageParser.Package p = i.next();
                if (p.applicationInfo != null
                        && (p.applicationInfo.flags&ApplicationInfo.FLAG_PERSISTENT) != 0
                        && (!mSafeMode || (p.applicationInfo.flags
                                &ApplicationInfo.FLAG_SYSTEM) != 0)) {
                    finalList.add(p.applicationInfo);
                }
            }
        }

        return finalList;
    }

    public ProviderInfo resolveContentProvider(String name, int flags) {
        synchronized (mPackages) {
            final PackageParser.Provider provider = mProviders.get(name);
            return provider != null
                    && mSettings.isEnabledLP(provider.info, flags)
                    && (!mSafeMode || (provider.info.applicationInfo.flags
                            &ApplicationInfo.FLAG_SYSTEM) != 0)
                    ? PackageParser.generateProviderInfo(provider, flags)
                    : null;
        }
    }

    /**
     * @deprecated
     */
    public void querySyncProviders(List outNames, List outInfo) {
        synchronized (mPackages) {
            Iterator<Map.Entry<String, PackageParser.Provider>> i
                = mProviders.entrySet().iterator();

            while (i.hasNext()) {
                Map.Entry<String, PackageParser.Provider> entry = i.next();
                PackageParser.Provider p = entry.getValue();

                if (p.syncable
                        && (!mSafeMode || (p.info.applicationInfo.flags
                                &ApplicationInfo.FLAG_SYSTEM) != 0)) {
                    outNames.add(entry.getKey());
                    outInfo.add(PackageParser.generateProviderInfo(p, 0));
                }
            }
        }
    }

    public List<ProviderInfo> queryContentProviders(String processName,
            int uid, int flags) {
        ArrayList<ProviderInfo> finalList = null;

        synchronized (mPackages) {
            Iterator<PackageParser.Provider> i = mProvidersByComponent.values().iterator();
            while (i.hasNext()) {
                PackageParser.Provider p = i.next();
                if (p.info.authority != null
                    && (processName == null ||
                            (p.info.processName.equals(processName)
                                    && p.info.applicationInfo.uid == uid))
                    && mSettings.isEnabledLP(p.info, flags)
                    && (!mSafeMode || (p.info.applicationInfo.flags
                            &ApplicationInfo.FLAG_SYSTEM) != 0)) {
                    if (finalList == null) {
                        finalList = new ArrayList<ProviderInfo>(3);
                    }
                    finalList.add(PackageParser.generateProviderInfo(p,
                            flags));
                }
            }
        }

        if (finalList != null) {
            Collections.sort(finalList, mProviderInitOrderSorter);
        }

        return finalList;
    }

    public InstrumentationInfo getInstrumentationInfo(ComponentName name,
            int flags) {
        synchronized (mPackages) {
            final PackageParser.Instrumentation i = mInstrumentation.get(name);
            return PackageParser.generateInstrumentationInfo(i, flags);
        }
    }

    public List<InstrumentationInfo> queryInstrumentation(String targetPackage,
            int flags) {
        ArrayList<InstrumentationInfo> finalList =
            new ArrayList<InstrumentationInfo>();

        synchronized (mPackages) {
            Iterator<PackageParser.Instrumentation> i = mInstrumentation.values().iterator();
            while (i.hasNext()) {
                PackageParser.Instrumentation p = i.next();
                if (targetPackage == null
                        || targetPackage.equals(p.info.targetPackage)) {
                    finalList.add(PackageParser.generateInstrumentationInfo(p,
                            flags));
                }
            }
        }

        return finalList;
    }

    private void scanDirLI(File dir, int flags, int scanMode) {
        Log.d(TAG, "Scanning app dir " + dir);

        String[] files = dir.list();

        int i;
        for (i=0; i<files.length; i++) {
            File file = new File(dir, files[i]);
            if (!isPackageFilename(files[i])) {
                // Ignore entries which are not apk's
                continue;
            }
            PackageParser.Package pkg = scanPackageLI(file,
                    flags|PackageParser.PARSE_MUST_BE_APK, scanMode);
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
    
    private static void reportSettingsProblem(int priority, String msg) {
        try {
            File fname = getSettingsProblemFile();
            FileOutputStream out = new FileOutputStream(fname, true);
            PrintWriter pw = new PrintWriter(out);
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
                    && ps.getTimeStamp() == srcFile.lastModified()) {
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
            int parseFlags, int scanMode) {
        mLastScanError = PackageManager.INSTALL_SUCCEEDED;
        String scanPath = scanFile.getPath();
        parseFlags |= mDefParseFlags;
        PackageParser pp = new PackageParser(scanPath);
        pp.setSeparateProcesses(mSeparateProcesses);
        final PackageParser.Package pkg = pp.parsePackage(scanFile,
                scanPath, mMetrics, parseFlags);
        if (pkg == null) {
            mLastScanError = pp.getParseError();
            return null;
        }
        PackageSetting ps = null;
        PackageSetting updatedPkg;
        synchronized (mPackages) {
            // Look to see if we already know about this package.
            String oldName = mSettings.mRenamedPackages.get(pkg.packageName);
            if (pkg.mOriginalPackages != null && pkg.mOriginalPackages.contains(oldName)) {
                // This package has been renamed to its original name.  Let's
                // use that.
                ps = mSettings.peekPackageLP(oldName);
            }
            // If there was no original package, see one for the real package name.
            if (ps == null) {
                ps = mSettings.peekPackageLP(pkg.packageName);
            }
            // Check to see if this package could be hiding/updating a system
            // package.  Must look for it either under the original or real
            // package name depending on our state.
            updatedPkg = mSettings.mDisabledSysPackages.get(
                    ps != null ? ps.name : pkg.packageName);
        }
        // First check if this is a system package that may involve an update
        if (updatedPkg != null && (parseFlags&PackageParser.PARSE_IS_SYSTEM) != 0) {
            if (!ps.codePath.equals(scanFile)) {
                // The path has changed from what was last scanned...  check the
                // version of the new path against what we have stored to determine
                // what to do.
                if (pkg.mVersionCode < ps.versionCode) {
                    // The system package has been updated and the code path does not match
                    // Ignore entry. Skip it.
                    Log.i(TAG, "Package " + ps.name + " at " + scanFile
                            + "ignored: updated version " + ps.versionCode
                            + " better than this " + pkg.mVersionCode);
                    mLastScanError = PackageManager.INSTALL_FAILED_DUPLICATE_PACKAGE;
                    return null;
                } else {
                    // The current app on the system partion is better than
                    // what we have updated to on the data partition; switch
                    // back to the system partition version.
                    // At this point, its safely assumed that package installation for
                    // apps in system partition will go through. If not there won't be a working
                    // version of the app
                    synchronized (mPackages) {
                        // Just remove the loaded entries from package lists.
                        mPackages.remove(ps.name);
                    }
                    Slog.w(TAG, "Package " + ps.name + " at " + scanFile
                            + "reverting from " + ps.codePathString
                            + ": new version " + pkg.mVersionCode
                            + " better than installed " + ps.versionCode);
                    InstallArgs args = new FileInstallArgs(ps.codePathString, ps.resourcePathString);
                    args.cleanUpResourcesLI();
                    removeNativeBinariesLI(pkg);
                    mSettings.enableSystemPackageLP(ps.name);
                }
            }
        }
        if (updatedPkg != null) {
            // An updated system app will not have the PARSE_IS_SYSTEM flag set initially
            parseFlags |= PackageParser.PARSE_IS_SYSTEM;
        }
        // Verify certificates against what was last scanned
        if (!collectCertificatesLI(pp, ps, pkg, scanFile, parseFlags)) {
            Slog.w(TAG, "Failed verifying certificates for package:" + pkg.packageName);
            return null;
        }
        // The apk is forward locked (not public) if its code and resources
        // are kept in different files.
        // TODO grab this value from PackageSettings
        if (ps != null && !ps.codePath.equals(ps.resourcePath)) {
            parseFlags |= PackageParser.PARSE_FORWARD_LOCK;
        }

        String codePath = null;
        String resPath = null;
        if ((parseFlags & PackageParser.PARSE_FORWARD_LOCK) != 0) {
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
        return scanPackageLI(pkg, parseFlags, scanMode | SCAN_UPDATE_SIGNATURE);
    }

    private static void setApplicationInfoPaths(PackageParser.Package pkg,
            String destCodePath, String destResPath) {
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
            if (checkSignaturesLP(pkgSetting.signatures.mSignatures, pkg.mSignatures) !=
                PackageManager.SIGNATURE_MATCH) {
                    Slog.e(TAG, "Package " + pkg.packageName
                            + " signatures do not match the previously installed version; ignoring!");
                    mLastScanError = PackageManager.INSTALL_FAILED_UPDATE_INCOMPATIBLE;
                    return false;
                }
        }
        // Check for shared user signatures
        if (pkgSetting.sharedUser != null && pkgSetting.sharedUser.signatures.mSignatures != null) {
            if (checkSignaturesLP(pkgSetting.sharedUser.signatures.mSignatures,
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

    public boolean performDexOpt(String packageName) {
        if (!mNoDexOpt) {
            return false;
        }

        PackageParser.Package p;
        synchronized (mPackages) {
            p = mPackages.get(packageName);
            if (p == null || p.mDidDexOpt) {
                return false;
            }
        }
        synchronized (mInstallLock) {
            return performDexOptLI(p, false) == DEX_OPT_PERFORMED;
        }
    }

    static final int DEX_OPT_SKIPPED = 0;
    static final int DEX_OPT_PERFORMED = 1;
    static final int DEX_OPT_FAILED = -1;

    private int performDexOptLI(PackageParser.Package pkg, boolean forceDex) {
        boolean performed = false;
        if ((pkg.applicationInfo.flags&ApplicationInfo.FLAG_HAS_CODE) != 0 && mInstaller != null) {
            String path = pkg.mScanPath;
            int ret = 0;
            try {
                if (forceDex || dalvik.system.DexFile.isDexOptNeeded(path)) {
                    ret = mInstaller.dexopt(path, pkg.applicationInfo.uid,
                            !isForwardLocked(pkg));
                    pkg.mDidDexOpt = true;
                    performed = true;
                }
            } catch (FileNotFoundException e) {
                Slog.w(TAG, "Apk not found for dexopt: " + path);
                ret = -1;
            } catch (IOException e) {
                Slog.w(TAG, "IOException reading apk: " + path, e);
                ret = -1;
            } catch (dalvik.system.StaleDexCacheError e) {
                Slog.w(TAG, "StaleDexCacheError when reading apk: " + path, e);
                ret = -1;
            } catch (Exception e) {
                Slog.w(TAG, "Exception when doing dexopt : ", e);
                ret = -1;
            }
            if (ret < 0) {
                //error from installer
                return DEX_OPT_FAILED;
            }
        }

        return performed ? DEX_OPT_PERFORMED : DEX_OPT_SKIPPED;
    }
    
    private boolean verifyPackageUpdate(PackageSetting oldPkg, PackageParser.Package newPkg) {
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

    private File getDataPathForPackage(PackageParser.Package pkg) {
        return new File(mAppDataDir, pkg.packageName);
    }
    
    private PackageParser.Package scanPackageLI(PackageParser.Package pkg,
            int parseFlags, int scanMode) {
        File scanFile = new File(pkg.mScanPath);
        if (scanFile == null || pkg.applicationInfo.sourceDir == null ||
                pkg.applicationInfo.publicSourceDir == null) {
            // Bail out. The resource and code paths haven't been set.
            Slog.w(TAG, " Code and resource paths haven't been set correctly");
            mLastScanError = PackageManager.INSTALL_FAILED_INVALID_APK;
            return null;
        }
        mScanningPath = scanFile;
        if (pkg == null) {
            mLastScanError = PackageManager.INSTALL_PARSE_FAILED_BAD_PACKAGE_NAME;
            return null;
        }

        if ((parseFlags&PackageParser.PARSE_IS_SYSTEM) != 0) {
            pkg.applicationInfo.flags |= ApplicationInfo.FLAG_SYSTEM;
        }

        if (pkg.packageName.equals("android")) {
            synchronized (mPackages) {
                if (mAndroidApplication != null) {
                    Slog.w(TAG, "*************************************************");
                    Slog.w(TAG, "Core android package being redefined.  Skipping.");
                    Slog.w(TAG, " file=" + mScanningPath);
                    Slog.w(TAG, "*************************************************");
                    mLastScanError = PackageManager.INSTALL_FAILED_DUPLICATE_PACKAGE;
                    return null;
                }

                // Set up information for our fall-back user intent resolution
                // activity.
                mPlatformPackage = pkg;
                pkg.mVersionCode = mSdkVersion;
                mAndroidApplication = pkg.applicationInfo;
                mResolveActivity.applicationInfo = mAndroidApplication;
                mResolveActivity.name = ResolverActivity.class.getName();
                mResolveActivity.packageName = mAndroidApplication.packageName;
                mResolveActivity.processName = mAndroidApplication.processName;
                mResolveActivity.launchMode = ActivityInfo.LAUNCH_MULTIPLE;
                mResolveActivity.flags = ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS;
                mResolveActivity.theme = com.android.internal.R.style.Theme_Dialog_Alert;
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

        if ((parseFlags&PackageParser.PARSE_CHATTY) != 0 && Config.LOGD) Log.d(
                TAG, "Scanning package " + pkg.packageName);
        if (mPackages.containsKey(pkg.packageName)
                || mSharedLibraries.containsKey(pkg.packageName)) {
            Slog.w(TAG, "*************************************************");
            Slog.w(TAG, "Application package " + pkg.packageName
                    + " already installed.  Skipping duplicate.");
            Slog.w(TAG, "*************************************************");
            mLastScanError = PackageManager.INSTALL_FAILED_DUPLICATE_PACKAGE;
            return null;
        }

        // Initialize package source and resource directories
        File destCodeFile = new File(pkg.applicationInfo.sourceDir);
        File destResourceFile = new File(pkg.applicationInfo.publicSourceDir);

        SharedUserSetting suid = null;
        PackageSetting pkgSetting = null;

        if ((pkg.applicationInfo.flags&ApplicationInfo.FLAG_SYSTEM) == 0) {
            // Only system apps can use these features.
            pkg.mOriginalPackages = null;
            pkg.mRealPackage = null;
            pkg.mAdoptPermissions = null;
        }
        
        synchronized (mPackages) {
            // Check all shared libraries and map to their actual file path.
            if (pkg.usesLibraries != null || pkg.usesOptionalLibraries != null) {
                if (mTmpSharedLibraries == null ||
                        mTmpSharedLibraries.length < mSharedLibraries.size()) {
                    mTmpSharedLibraries = new String[mSharedLibraries.size()];
                }
                int num = 0;
                int N = pkg.usesLibraries != null ? pkg.usesLibraries.size() : 0;
                for (int i=0; i<N; i++) {
                    String file = mSharedLibraries.get(pkg.usesLibraries.get(i));
                    if (file == null) {
                        Slog.e(TAG, "Package " + pkg.packageName
                                + " requires unavailable shared library "
                                + pkg.usesLibraries.get(i) + "; failing!");
                        mLastScanError = PackageManager.INSTALL_FAILED_MISSING_SHARED_LIBRARY;
                        return null;
                    }
                    mTmpSharedLibraries[num] = file;
                    num++;
                }
                N = pkg.usesOptionalLibraries != null ? pkg.usesOptionalLibraries.size() : 0;
                for (int i=0; i<N; i++) {
                    String file = mSharedLibraries.get(pkg.usesOptionalLibraries.get(i));
                    if (file == null) {
                        Slog.w(TAG, "Package " + pkg.packageName
                                + " desires unavailable shared library "
                                + pkg.usesOptionalLibraries.get(i) + "; ignoring!");
                    } else {
                        mTmpSharedLibraries[num] = file;
                        num++;
                    }
                }
                if (num > 0) {
                    pkg.usesLibraryFiles = new String[num];
                    System.arraycopy(mTmpSharedLibraries, 0,
                            pkg.usesLibraryFiles, 0, num);
                }

                if (pkg.reqFeatures != null) {
                    N = pkg.reqFeatures.size();
                    for (int i=0; i<N; i++) {
                        FeatureInfo fi = pkg.reqFeatures.get(i);
                        if ((fi.flags&FeatureInfo.FLAG_REQUIRED) == 0) {
                            // Don't care.
                            continue;
                        }

                        if (fi.name != null) {
                            if (mAvailableFeatures.get(fi.name) == null) {
                                Slog.e(TAG, "Package " + pkg.packageName
                                        + " requires unavailable feature "
                                        + fi.name + "; failing!");
                                mLastScanError = PackageManager.INSTALL_FAILED_MISSING_FEATURE;
                                return null;
                            }
                        }
                    }
                }
            }

            if (pkg.mSharedUserId != null) {
                suid = mSettings.getSharedUserLP(pkg.mSharedUserId,
                        pkg.applicationInfo.flags, true);
                if (suid == null) {
                    Slog.w(TAG, "Creating application package " + pkg.packageName
                            + " for shared user failed");
                    mLastScanError = PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE;
                    return null;
                }
                if ((parseFlags&PackageParser.PARSE_CHATTY) != 0 && Config.LOGD) {
                    Log.d(TAG, "Shared UserID " + pkg.mSharedUserId + " (uid="
                            + suid.userId + "): packages=" + suid.packages);
                }
            }

            if (false) {
                if (pkg.mOriginalPackages != null) {
                    Log.w(TAG, "WAITING FOR DEBUGGER");
                    Debug.waitForDebugger();
                    Log.i(TAG, "Package " + pkg.packageName + " from original packages"
                            + pkg.mOriginalPackages);
                }
            }
            
            // Check if we are renaming from an original package name.
            PackageSetting origPackage = null;
            String realName = null;
            if (pkg.mOriginalPackages != null) {
                // This package may need to be renamed to a previously
                // installed name.  Let's check on that...
                String renamed = mSettings.mRenamedPackages.get(pkg.mRealPackage);
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
                        if ((origPackage=mSettings.peekPackageLP(
                                pkg.mOriginalPackages.get(i))) != null) {
                            // We do have the package already installed under its
                            // original name...  should we use it?
                            if (!verifyPackageUpdate(origPackage, pkg)) {
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
            pkgSetting = mSettings.getPackageLP(pkg, origPackage, realName, suid, destCodeFile,
                            destResourceFile, pkg.applicationInfo.flags, true, false);
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
            
            if (mSettings.mDisabledSysPackages.get(pkg.packageName) != null) {
                pkg.applicationInfo.flags |= ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
            }

            pkg.applicationInfo.uid = pkgSetting.userId;
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
                    if (checkSignaturesLP(pkgSetting.sharedUser.signatures.mSignatures,
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
                int N = pkg.providers.size();
                int i;
                for (i=0; i<N; i++) {
                    PackageParser.Provider p = pkg.providers.get(i);
                    if (p.info.authority != null) {
                        String names[] = p.info.authority.split(";");
                        for (int j = 0; j < names.length; j++) {
                            if (mProviders.containsKey(names[j])) {
                                PackageParser.Provider other = mProviders.get(names[j]);
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
        }

        final String pkgName = pkg.packageName;
        
        if (pkg.mAdoptPermissions != null) {
            // This package wants to adopt ownership of permissions from
            // another package.
            for (int i=pkg.mAdoptPermissions.size()-1; i>=0; i--) {
                String origName = pkg.mAdoptPermissions.get(i);
                PackageSetting orig = mSettings.peekPackageLP(origName);
                if (orig != null) {
                    if (verifyPackageUpdate(orig, pkg)) {
                        Slog.i(TAG, "Adopting permissions from "
                                + origName + " to " + pkg.packageName);
                        mSettings.transferPermissions(origName, pkg.packageName);
                    }
                }
            }
        }
        
        long scanFileTime = scanFile.lastModified();
        final boolean forceDex = (scanMode&SCAN_FORCE_DEX) != 0;
        final boolean scanFileNewer = forceDex || scanFileTime != pkgSetting.getTimeStamp();
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
            dataPath = getDataPathForPackage(pkg);
            
            boolean uidError = false;
            
            if (dataPath.exists()) {
                mOutPermissions[1] = 0;
                FileUtils.getPermissions(dataPath.getPath(), mOutPermissions);
                if (mOutPermissions[1] == pkg.applicationInfo.uid
                        || !Process.supportsProcesses()) {
                    pkg.applicationInfo.dataDir = dataPath.getPath();
                } else {
                    boolean recovered = false;
                    if ((parseFlags&PackageParser.PARSE_IS_SYSTEM) != 0) {
                        // If this is a system app, we can at least delete its
                        // current data so the application will still work.
                        if (mInstaller != null) {
                            int ret = mInstaller.remove(pkgName);
                            if (ret >= 0) {
                                // Old data gone!
                                String msg = "System package " + pkg.packageName
                                        + " has changed from uid: "
                                        + mOutPermissions[1] + " to "
                                        + pkg.applicationInfo.uid + "; old data erased";
                                reportSettingsProblem(Log.WARN, msg);
                                recovered = true;

                                // And now re-install the app.
                                ret = mInstaller.install(pkgName, pkg.applicationInfo.uid,
                                        pkg.applicationInfo.uid);
                                if (ret == -1) {
                                    // Ack should not happen!
                                    msg = "System package " + pkg.packageName
                                            + " could not have data directory re-created after delete.";
                                    reportSettingsProblem(Log.WARN, msg);
                                    mLastScanError = PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE;
                                    return null;
                                }
                            }
                        }
                        if (!recovered) {
                            mHasSystemUidErrors = true;
                        }
                    }
                    if (!recovered) {
                        pkg.applicationInfo.dataDir = "/mismatched_uid/settings_"
                            + pkg.applicationInfo.uid + "/fs_"
                            + mOutPermissions[1];
                        String msg = "Package " + pkg.packageName
                                + " has mismatched uid: "
                                + mOutPermissions[1] + " on disk, "
                                + pkg.applicationInfo.uid + " in settings";
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
            } else {
                if ((parseFlags&PackageParser.PARSE_CHATTY) != 0 && Config.LOGV)
                    Log.v(TAG, "Want this data dir: " + dataPath);
                //invoke installer to do the actual installation
                if (mInstaller != null) {
                    int ret = mInstaller.install(pkgName, pkg.applicationInfo.uid,
                            pkg.applicationInfo.uid);
                    if(ret < 0) {
                        // Error from installer
                        mLastScanError = PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE;
                        return null;
                    }
                } else {
                    dataPath.mkdirs();
                    if (dataPath.exists()) {
                        FileUtils.setPermissions(
                            dataPath.toString(),
                            FileUtils.S_IRWXU|FileUtils.S_IRWXG|FileUtils.S_IXOTH,
                            pkg.applicationInfo.uid, pkg.applicationInfo.uid);
                    }
                }
                if (dataPath.exists()) {
                    pkg.applicationInfo.dataDir = dataPath.getPath();
                } else {
                    Slog.w(TAG, "Unable to create data directory: " + dataPath);
                    pkg.applicationInfo.dataDir = null;
                }
            }
            
            pkgSetting.uidError = uidError;
        }

        // Perform shared library installation and dex validation and
        // optimization, if this is not a system app.
        if (mInstaller != null) {
            String path = scanFile.getPath();
            if (scanFileNewer) {
                // Note: We don't want to unpack the native binaries for
                //       system applications, unless they have been updated
                //       (the binaries are already under /system/lib).
                //
                //       In other words, we're going to unpack the binaries
                //       only for non-system apps and system app upgrades.
                //
                int flags = pkg.applicationInfo.flags;
                if ((flags & ApplicationInfo.FLAG_SYSTEM) == 0 ||
                    (flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) {
                    Log.i(TAG, path + " changed; unpacking");
                    int err = cachePackageSharedLibsLI(pkg, scanFile);
                    if (err != PackageManager.INSTALL_SUCCEEDED) {
                        mLastScanError = err;
                        return null;
                    }
                }
            }
            pkg.mScanPath = path;

            if ((scanMode&SCAN_NO_DEX) == 0) {
                if (performDexOptLI(pkg, forceDex) == DEX_OPT_FAILED) {
                    mLastScanError = PackageManager.INSTALL_FAILED_DEXOPT;
                    return null;
                }
            }
        }

        if (mFactoryTest && pkg.requestedPermissions.contains(
                android.Manifest.permission.FACTORY_TEST)) {
            pkg.applicationInfo.flags |= ApplicationInfo.FLAG_FACTORY_TEST;
        }

        // Request the ActivityManager to kill the process(only for existing packages)
        // so that we do not end up in a confused state while the user is still using the older
        // version of the application while the new one gets installed.
        if ((parseFlags & PackageManager.INSTALL_REPLACE_EXISTING) != 0) {
            killApplication(pkg.applicationInfo.packageName,
                        pkg.applicationInfo.uid);
        }

        synchronized (mPackages) {
            // We don't expect installation to fail beyond this point,
            if ((scanMode&SCAN_MONITOR) != 0) {
                mAppDirs.put(pkg.mPath, pkg);
            }
            // Add the new setting to mSettings
            mSettings.insertPackageSettingLP(pkgSetting, pkg);
            // Add the new setting to mPackages
            mPackages.put(pkg.applicationInfo.packageName, pkg);
            // Make sure we don't accidentally delete its data.
            mSettings.mPackagesToBeCleaned.remove(pkgName);
            
            int N = pkg.providers.size();
            StringBuilder r = null;
            int i;
            for (i=0; i<N; i++) {
                PackageParser.Provider p = pkg.providers.get(i);
                p.info.processName = fixProcessName(pkg.applicationInfo.processName,
                        p.info.processName, pkg.applicationInfo.uid);
                mProvidersByComponent.put(new ComponentName(p.info.packageName,
                        p.info.name), p);
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
                        if (!mProviders.containsKey(names[j])) {
                            mProviders.put(names[j], p);
                            if (p.info.authority == null) {
                                p.info.authority = names[j];
                            } else {
                                p.info.authority = p.info.authority + ";" + names[j];
                            }
                            if ((parseFlags&PackageParser.PARSE_CHATTY) != 0 && Config.LOGD)
                                Log.d(TAG, "Registered content provider: " + names[j] +
                                        ", className = " + p.info.name +
                                        ", isSyncable = " + p.info.isSyncable);
                        } else {
                            PackageParser.Provider other = mProviders.get(names[j]);
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
                if (Config.LOGD) Log.d(TAG, "  Providers: " + r);
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
                if (Config.LOGD) Log.d(TAG, "  Services: " + r);
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
                if (Config.LOGD) Log.d(TAG, "  Receivers: " + r);
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
                if (Config.LOGD) Log.d(TAG, "  Activities: " + r);
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
                if (Config.LOGD) Log.d(TAG, "  Permission Groups: " + r);
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
                if (Config.LOGD) Log.d(TAG, "  Permissions: " + r);
            }

            N = pkg.instrumentation.size();
            r = null;
            for (i=0; i<N; i++) {
                PackageParser.Instrumentation a = pkg.instrumentation.get(i);
                a.info.packageName = pkg.applicationInfo.packageName;
                a.info.sourceDir = pkg.applicationInfo.sourceDir;
                a.info.publicSourceDir = pkg.applicationInfo.publicSourceDir;
                a.info.dataDir = pkg.applicationInfo.dataDir;
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
                if (Config.LOGD) Log.d(TAG, "  Instrumentation: " + r);
            }

            if (pkg.protectedBroadcasts != null) {
                N = pkg.protectedBroadcasts.size();
                for (i=0; i<N; i++) {
                    mProtectedBroadcasts.add(pkg.protectedBroadcasts.get(i));
                }
            }

            pkgSetting.setTimeStamp(scanFileTime);
        }

        return pkg;
    }

    private void killApplication(String pkgName, int uid) {
        // Request the ActivityManager to kill the process(only for existing packages)
        // so that we do not end up in a confused state while the user is still using the older
        // version of the application while the new one gets installed.
        IActivityManager am = ActivityManagerNative.getDefault();
        if (am != null) {
            try {
                am.killApplicationWithUid(pkgName, uid);
            } catch (RemoteException e) {
            }
        }
    }

    // The following constants are returned by cachePackageSharedLibsForAbiLI
    // to indicate if native shared libraries were found in the package.
    // Values are:
    //    PACKAGE_INSTALL_NATIVE_FOUND_LIBRARIES => native libraries found and installed
    //    PACKAGE_INSTALL_NATIVE_NO_LIBRARIES     => no native libraries in package
    //    PACKAGE_INSTALL_NATIVE_ABI_MISMATCH     => native libraries for another ABI found
    //                                        in package (and not installed)
    //
    private static final int PACKAGE_INSTALL_NATIVE_FOUND_LIBRARIES = 0;
    private static final int PACKAGE_INSTALL_NATIVE_NO_LIBRARIES = 1;
    private static final int PACKAGE_INSTALL_NATIVE_ABI_MISMATCH = 2;

    // Return the path of the directory that will contain the native binaries
    // of a given installed package. This is relative to the data path.
    //
    private static File getNativeBinaryDirForPackage(PackageParser.Package pkg) {
        return new File(pkg.applicationInfo.dataDir + "/lib");
    }

    // Find all files of the form lib/<cpuAbi>/lib<name>.so in the .apk
    // and automatically copy them to /data/data/<appname>/lib if present.
    //
    // NOTE: this method may throw an IOException if the library cannot
    // be copied to its final destination, e.g. if there isn't enough
    // room left on the data partition, or a ZipException if the package
    // file is malformed.
    //
    private int cachePackageSharedLibsForAbiLI(PackageParser.Package pkg,
        File scanFile, String cpuAbi) throws IOException, ZipException {
        File sharedLibraryDir = getNativeBinaryDirForPackage(pkg);
        final String apkLib = "lib/";
        final int apkLibLen = apkLib.length();
        final int cpuAbiLen = cpuAbi.length();
        final String libPrefix = "lib";
        final int libPrefixLen = libPrefix.length();
        final String libSuffix = ".so";
        final int libSuffixLen = libSuffix.length();
        boolean hasNativeLibraries = false;
        boolean installedNativeLibraries = false;

        // the minimum length of a valid native shared library of the form
        // lib/<something>/lib<name>.so.
        final int minEntryLen  = apkLibLen + 2 + libPrefixLen + 1 + libSuffixLen;

        ZipFile zipFile = new ZipFile(scanFile);
        Enumeration<ZipEntry> entries =
            (Enumeration<ZipEntry>) zipFile.entries();

        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            // skip directories
            if (entry.isDirectory()) {
                continue;
            }
            String entryName = entry.getName();

            // check that the entry looks like lib/<something>/lib<name>.so
            // here, but don't check the ABI just yet.
            //
            // - must be sufficiently long
            // - must end with libSuffix, i.e. ".so"
            // - must start with apkLib, i.e. "lib/"
            if (entryName.length() < minEntryLen ||
                !entryName.endsWith(libSuffix) ||
                !entryName.startsWith(apkLib) ) {
                continue;
            }

            // file name must start with libPrefix, i.e. "lib"
            int lastSlash = entryName.lastIndexOf('/');

            if (lastSlash < 0 ||
                !entryName.regionMatches(lastSlash+1, libPrefix, 0, libPrefixLen) ) {
                continue;
            }

            hasNativeLibraries = true;

            // check the cpuAbi now, between lib/ and /lib<name>.so
            //
            if (lastSlash != apkLibLen + cpuAbiLen ||
                !entryName.regionMatches(apkLibLen, cpuAbi, 0, cpuAbiLen) )
                continue;

            // extract the library file name, ensure it doesn't contain
            // weird characters. we're guaranteed here that it doesn't contain
            // a directory separator though.
            String libFileName = entryName.substring(lastSlash+1);
            if (!FileUtils.isFilenameSafe(new File(libFileName))) {
                continue;
            }

            installedNativeLibraries = true;

            // Always extract the shared library
            String sharedLibraryFilePath = sharedLibraryDir.getPath() +
                File.separator + libFileName;
            File sharedLibraryFile = new File(sharedLibraryFilePath);

            if (Config.LOGD) {
                Log.d(TAG, "Caching shared lib " + entry.getName());
            }
            if (mInstaller == null) {
                sharedLibraryDir.mkdir();
            }
            cacheNativeBinaryLI(pkg, zipFile, entry, sharedLibraryDir,
                    sharedLibraryFile);
        }
        if (!hasNativeLibraries)
            return PACKAGE_INSTALL_NATIVE_NO_LIBRARIES;

        if (!installedNativeLibraries)
            return PACKAGE_INSTALL_NATIVE_ABI_MISMATCH;

        return PACKAGE_INSTALL_NATIVE_FOUND_LIBRARIES;
    }

    // Find the gdbserver executable program in a package at
    // lib/<cpuAbi>/gdbserver and copy it to /data/data/<name>/lib/gdbserver
    //
    // Returns PACKAGE_INSTALL_NATIVE_FOUND_LIBRARIES on success,
    // or PACKAGE_INSTALL_NATIVE_NO_LIBRARIES otherwise.
    //
    private int cachePackageGdbServerLI(PackageParser.Package pkg,
        File scanFile, String cpuAbi) throws IOException, ZipException {
        File installGdbServerDir = getNativeBinaryDirForPackage(pkg);
        final String GDBSERVER = "gdbserver";
        final String apkGdbServerPath = "lib/" + cpuAbi + "/" + GDBSERVER;

        ZipFile zipFile = new ZipFile(scanFile);
        Enumeration<ZipEntry> entries =
            (Enumeration<ZipEntry>) zipFile.entries();

        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            // skip directories
            if (entry.isDirectory()) {
                continue;
            }
            String entryName = entry.getName();

            if (!entryName.equals(apkGdbServerPath)) {
                continue;
            }

            String installGdbServerPath = installGdbServerDir.getPath() +
                "/" + GDBSERVER;
            File installGdbServerFile = new File(installGdbServerPath);

            if (Config.LOGD) {
                Log.d(TAG, "Caching gdbserver " + entry.getName());
            }
            if (mInstaller == null) {
                installGdbServerDir.mkdir();
            }
            cacheNativeBinaryLI(pkg, zipFile, entry, installGdbServerDir,
                    installGdbServerFile);

            return PACKAGE_INSTALL_NATIVE_FOUND_LIBRARIES;
        }
        return PACKAGE_INSTALL_NATIVE_NO_LIBRARIES;
    }

    // extract shared libraries stored in the APK as lib/<cpuAbi>/lib<name>.so
    // and copy them to /data/data/<appname>/lib.
    //
    // This function will first try the main CPU ABI defined by Build.CPU_ABI
    // (which corresponds to ro.product.cpu.abi), and also try an alternate
    // one if ro.product.cpu.abi2 is defined.
    //
    private int cachePackageSharedLibsLI(PackageParser.Package pkg, File scanFile) {
        // Remove all native binaries from a directory. This is used when upgrading
        // a package: in case the new .apk doesn't contain a native binary that was
        // in the old one (and thus installed), we need to remove it from
        // /data/data/<appname>/lib
        //
        // The simplest way to do that is to remove all files in this directory,
        // since it is owned by "system", applications are not supposed to write
        // anything there.
        removeNativeBinariesLI(pkg);

        String cpuAbi = Build.CPU_ABI;
        try {
            int result = cachePackageSharedLibsForAbiLI(pkg, scanFile, cpuAbi);

            // some architectures are capable of supporting several CPU ABIs
            // for example, 'armeabi-v7a' also supports 'armeabi' native code
            // this is indicated by the definition of the ro.product.cpu.abi2
            // system property.
            //
            // only scan the package twice in case of ABI mismatch
            if (result == PACKAGE_INSTALL_NATIVE_ABI_MISMATCH) {
                final String cpuAbi2 = SystemProperties.get("ro.product.cpu.abi2",null);
                if (cpuAbi2 != null) {
                    result = cachePackageSharedLibsForAbiLI(pkg, scanFile, cpuAbi2);
                }

                if (result == PACKAGE_INSTALL_NATIVE_ABI_MISMATCH) {
                    Slog.w(TAG,"Native ABI mismatch from package file");
                    return PackageManager.INSTALL_FAILED_INVALID_APK;
                }

                if (result == PACKAGE_INSTALL_NATIVE_FOUND_LIBRARIES) {
                    cpuAbi = cpuAbi2;
                }
            }

            // for debuggable packages, also extract gdbserver from lib/<abi>
            // into /data/data/<appname>/lib too.
            if (result == PACKAGE_INSTALL_NATIVE_FOUND_LIBRARIES &&
                (pkg.applicationInfo.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                int result2 = cachePackageGdbServerLI(pkg, scanFile, cpuAbi);
                if (result2 == PACKAGE_INSTALL_NATIVE_FOUND_LIBRARIES) {
                    pkg.applicationInfo.flags |= ApplicationInfo.FLAG_NATIVE_DEBUGGABLE;
                }
            }
        } catch (ZipException e) {
            Slog.w(TAG, "Failed to extract data from package file", e);
            return PackageManager.INSTALL_FAILED_INVALID_APK;
        } catch (IOException e) {
            Slog.w(TAG, "Failed to cache package shared libs", e);
            return PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE;
        }
        return PackageManager.INSTALL_SUCCEEDED;
    }

    private void cacheNativeBinaryLI(PackageParser.Package pkg,
            ZipFile zipFile, ZipEntry entry,
            File binaryDir,
            File binaryFile) throws IOException {
        InputStream inputStream = zipFile.getInputStream(entry);
        try {
            File tempFile = File.createTempFile("tmp", "tmp", binaryDir);
            String tempFilePath = tempFile.getPath();
            // XXX package manager can't change owner, so the executable files for
            // now need to be left as world readable and owned by the system.
            if (! FileUtils.copyToFile(inputStream, tempFile) ||
                ! tempFile.setLastModified(entry.getTime()) ||
                FileUtils.setPermissions(tempFilePath,
                        FileUtils.S_IRUSR|FileUtils.S_IWUSR|FileUtils.S_IRGRP
                        |FileUtils.S_IXUSR|FileUtils.S_IXGRP|FileUtils.S_IXOTH
                        |FileUtils.S_IROTH, -1, -1) != 0 ||
                ! tempFile.renameTo(binaryFile)) {
                // Failed to properly write file.
                tempFile.delete();
                throw new IOException("Couldn't create cached binary "
                        + binaryFile + " in " + binaryDir);
            }
        } finally {
            inputStream.close();
        }
    }

    // Remove the native binaries of a given package. This simply
    // gets rid of the files in the 'lib' sub-directory.
    private void removeNativeBinariesLI(PackageParser.Package pkg) {
        File binaryDir = getNativeBinaryDirForPackage(pkg);

        if (DEBUG_NATIVE) {
            Slog.w(TAG,"Deleting native binaries from: " + binaryDir.getPath());
        }

        // Just remove any file in the directory. Since the directory
        // is owned by the 'system' UID, the application is not supposed
        // to have written anything there.
        //
        if (binaryDir.exists()) {
            File[]  binaries = binaryDir.listFiles();
            if (binaries != null) {
                for (int nn=0; nn < binaries.length; nn++) {
                    if (DEBUG_NATIVE) {
                        Slog.d(TAG,"    Deleting " + binaries[nn].getName());
                    }
                    if (!binaries[nn].delete()) {
                        Slog.w(TAG,"Could not delete native binary: " +
                                binaries[nn].getPath());
                    }
                }
            }
            // Do not delete 'lib' directory itself, or this will prevent
            // installation of future updates.
        }
    }

    void removePackageLI(PackageParser.Package pkg, boolean chatty) {
        if (chatty && Config.LOGD) Log.d(
            TAG, "Removing package " + pkg.applicationInfo.packageName );

        synchronized (mPackages) {
            clearPackagePreferredActivitiesLP(pkg.packageName);

            mPackages.remove(pkg.applicationInfo.packageName);
            if (pkg.mPath != null) {
                mAppDirs.remove(pkg.mPath);
            }

            PackageSetting ps = (PackageSetting)pkg.mExtras;
            if (ps != null && ps.sharedUser != null) {
                // XXX don't do this until the data is removed.
                if (false) {
                    ps.sharedUser.packages.remove(ps);
                    if (ps.sharedUser.packages.size() == 0) {
                        // Remove.
                    }
                }
            }

            int N = pkg.providers.size();
            StringBuilder r = null;
            int i;
            for (i=0; i<N; i++) {
                PackageParser.Provider p = pkg.providers.get(i);
                mProvidersByComponent.remove(new ComponentName(p.info.packageName,
                        p.info.name));
                if (p.info.authority == null) {

                    /* The is another ContentProvider with this authority when
                     * this app was installed so this authority is null,
                     * Ignore it as we don't have to unregister the provider.
                     */
                    continue;
                }
                String names[] = p.info.authority.split(";");
                for (int j = 0; j < names.length; j++) {
                    if (mProviders.get(names[j]) == p) {
                        mProviders.remove(names[j]);
                        if (chatty && Config.LOGD) Log.d(
                            TAG, "Unregistered content provider: " + names[j] +
                            ", className = " + p.info.name +
                            ", isSyncable = " + p.info.isSyncable);
                    }
                }
                if (chatty) {
                    if (r == null) {
                        r = new StringBuilder(256);
                    } else {
                        r.append(' ');
                    }
                    r.append(p.info.name);
                }
            }
            if (r != null) {
                if (Config.LOGD) Log.d(TAG, "  Providers: " + r);
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
                if (Config.LOGD) Log.d(TAG, "  Services: " + r);
            }

            N = pkg.receivers.size();
            r = null;
            for (i=0; i<N; i++) {
                PackageParser.Activity a = pkg.receivers.get(i);
                mReceivers.removeActivity(a, "receiver");
                if (chatty) {
                    if (r == null) {
                        r = new StringBuilder(256);
                    } else {
                        r.append(' ');
                    }
                    r.append(a.info.name);
                }
            }
            if (r != null) {
                if (Config.LOGD) Log.d(TAG, "  Receivers: " + r);
            }

            N = pkg.activities.size();
            r = null;
            for (i=0; i<N; i++) {
                PackageParser.Activity a = pkg.activities.get(i);
                mActivities.removeActivity(a, "activity");
                if (chatty) {
                    if (r == null) {
                        r = new StringBuilder(256);
                    } else {
                        r.append(' ');
                    }
                    r.append(a.info.name);
                }
            }
            if (r != null) {
                if (Config.LOGD) Log.d(TAG, "  Activities: " + r);
            }

            N = pkg.permissions.size();
            r = null;
            for (i=0; i<N; i++) {
                PackageParser.Permission p = pkg.permissions.get(i);
                boolean tree = false;
                BasePermission bp = mSettings.mPermissions.get(p.info.name);
                if (bp == null) {
                    tree = true;
                    bp = mSettings.mPermissionTrees.get(p.info.name);
                }
                if (bp != null && bp.perm == p) {
                    bp.perm = null;
                    if (chatty) {
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
                if (Config.LOGD) Log.d(TAG, "  Permissions: " + r);
            }

            N = pkg.instrumentation.size();
            r = null;
            for (i=0; i<N; i++) {
                PackageParser.Instrumentation a = pkg.instrumentation.get(i);
                mInstrumentation.remove(a.getComponentName());
                if (chatty) {
                    if (r == null) {
                        r = new StringBuilder(256);
                    } else {
                        r.append(' ');
                    }
                    r.append(a.info.name);
                }
            }
            if (r != null) {
                if (Config.LOGD) Log.d(TAG, "  Instrumentation: " + r);
            }
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
    
    private void updatePermissionsLP(String changingPkg,
            PackageParser.Package pkgInfo, boolean grantPermissions,
            boolean replace, boolean replaceAll) {
        // Make sure there are no dangling permission trees.
        Iterator<BasePermission> it = mSettings.mPermissionTrees
                .values().iterator();
        while (it.hasNext()) {
            BasePermission bp = it.next();
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
                    grantPermissions = true;
                    it.remove();
                }
            }
        }

        // Make sure all dynamic permissions have been assigned to a package,
        // and make sure there are no dangling permissions.
        it = mSettings.mPermissions.values().iterator();
        while (it.hasNext()) {
            BasePermission bp = it.next();
            if (bp.type == BasePermission.TYPE_DYNAMIC) {
                if (DEBUG_SETTINGS) Log.v(TAG, "Dynamic permission: name="
                        + bp.name + " pkg=" + bp.sourcePackage
                        + " info=" + bp.pendingInfo);
                if (bp.packageSetting == null && bp.pendingInfo != null) {
                    BasePermission tree = findPermissionTreeLP(bp.name);
                    if (tree != null) {
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
                    grantPermissions = true;
                    it.remove();
                }
            }
        }

        // Now update the permissions for all packages, in particular
        // replace the granted permissions of the system packages.
        if (grantPermissions) {
            for (PackageParser.Package pkg : mPackages.values()) {
                if (pkg != pkgInfo) {
                    grantPermissionsLP(pkg, replaceAll);
                }
            }
        }
        
        if (pkgInfo != null) {
            grantPermissionsLP(pkgInfo, replace);
        }
    }

    private void grantPermissionsLP(PackageParser.Package pkg, boolean replace) {
        final PackageSetting ps = (PackageSetting)pkg.mExtras;
        if (ps == null) {
            return;
        }
        final GrantedPermissions gp = ps.sharedUser != null ? ps.sharedUser : ps;
        boolean changedPermission = false;

        if (replace) {
            ps.permissionsFixed = false;
            if (gp == ps) {
                gp.grantedPermissions.clear();
                gp.gids = mGlobalGids;
            }
        }

        if (gp.gids == null) {
            gp.gids = mGlobalGids;
        }

        final int N = pkg.requestedPermissions.size();
        for (int i=0; i<N; i++) {
            String name = pkg.requestedPermissions.get(i);
            BasePermission bp = mSettings.mPermissions.get(name);
            if (false) {
                if (gp != ps) {
                    Log.i(TAG, "Package " + pkg.packageName + " checking " + name
                            + ": " + bp);
                }
            }
            if (bp != null && bp.packageSetting != null) {
                final String perm = bp.name;
                boolean allowed;
                boolean allowedSig = false;
                if (bp.protectionLevel == PermissionInfo.PROTECTION_NORMAL
                        || bp.protectionLevel == PermissionInfo.PROTECTION_DANGEROUS) {
                    allowed = true;
                } else if (bp.packageSetting == null) {
                    // This permission is invalid; skip it.
                    allowed = false;
                } else if (bp.protectionLevel == PermissionInfo.PROTECTION_SIGNATURE
                        || bp.protectionLevel == PermissionInfo.PROTECTION_SIGNATURE_OR_SYSTEM) {
                    allowed = (checkSignaturesLP(bp.packageSetting.signatures.mSignatures, pkg.mSignatures)
                                    == PackageManager.SIGNATURE_MATCH)
                            || (checkSignaturesLP(mPlatformPackage.mSignatures, pkg.mSignatures)
                                    == PackageManager.SIGNATURE_MATCH);
                    if (bp.protectionLevel == PermissionInfo.PROTECTION_SIGNATURE_OR_SYSTEM) {
                        if ((pkg.applicationInfo.flags&ApplicationInfo.FLAG_SYSTEM) != 0) {
                            // For updated system applications, the signatureOrSystem permission
                            // is granted only if it had been defined by the original application.
                            if ((pkg.applicationInfo.flags
                                    & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)  != 0) {
                                PackageSetting sysPs = mSettings.getDisabledSystemPkg(pkg.packageName);
                                if(sysPs.grantedPermissions.contains(perm)) {
                                    allowed = true;
                                } else {
                                    allowed = false;
                                }
                            } else {
                                allowed = true;
                            }
                        }
                    }
                    if (allowed) {
                        allowedSig = true;
                    }
                } else {
                    allowed = false;
                }
                if (false) {
                    if (gp != ps) {
                        Log.i(TAG, "Package " + pkg.packageName + " granting " + perm);
                    }
                }
                if (allowed) {
                    if ((ps.pkgFlags&ApplicationInfo.FLAG_SYSTEM) == 0
                            && ps.permissionsFixed) {
                        // If this is an existing, non-system package, then
                        // we can't add any new permissions to it.
                        if (!allowedSig && !gp.grantedPermissions.contains(perm)) {
                            allowed = false;
                            // Except...  if this is a permission that was added
                            // to the platform (note: need to only do this when
                            // updating the platform).
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
            } else {
                Slog.w(TAG, "Unknown permission " + name
                        + " in package " + pkg.packageName);
            }
        }

        if ((changedPermission || replace) && !ps.permissionsFixed &&
                ((ps.pkgFlags&ApplicationInfo.FLAG_SYSTEM) == 0) ||
                ((ps.pkgFlags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)){
            // This is the first that we have heard about this package, so the
            // permissions we have now selected are fixed until explicitly
            // changed.
            ps.permissionsFixed = true;
        }
        ps.haveGids = true;
    }
    
    private final class ActivityIntentResolver
            extends IntentResolver<PackageParser.ActivityIntentInfo, ResolveInfo> {
        public List queryIntent(Intent intent, String resolvedType, boolean defaultOnly) {
            mFlags = defaultOnly ? PackageManager.MATCH_DEFAULT_ONLY : 0;
            return super.queryIntent(intent, resolvedType, defaultOnly);
        }

        public List queryIntent(Intent intent, String resolvedType, int flags) {
            mFlags = flags;
            return super.queryIntent(intent, resolvedType,
                (flags&PackageManager.MATCH_DEFAULT_ONLY) != 0);
        }

        public List queryIntentForPackage(Intent intent, String resolvedType, int flags,
                                          ArrayList<PackageParser.Activity> packageActivities) {
            if (packageActivities == null) {
                return null;
            }
            mFlags = flags;
            final boolean defaultOnly = (flags&PackageManager.MATCH_DEFAULT_ONLY) != 0;
            int N = packageActivities.size();
            ArrayList<ArrayList<PackageParser.ActivityIntentInfo>> listCut =
                new ArrayList<ArrayList<PackageParser.ActivityIntentInfo>>(N);

            ArrayList<PackageParser.ActivityIntentInfo> intentFilters;
            for (int i = 0; i < N; ++i) {
                intentFilters = packageActivities.get(i).intents;
                if (intentFilters != null && intentFilters.size() > 0) {
                    listCut.add(intentFilters);
                }
            }
            return super.queryIntentFromList(intent, resolvedType, defaultOnly, listCut);
        }

        public final void addActivity(PackageParser.Activity a, String type) {
            mActivities.put(a.getComponentName(), a);
            if (SHOW_INFO || Config.LOGV) Log.v(
                TAG, "  " + type + " " +
                (a.info.nonLocalizedLabel != null ? a.info.nonLocalizedLabel : a.info.name) + ":");
            if (SHOW_INFO || Config.LOGV) Log.v(TAG, "    Class=" + a.info.name);
            int NI = a.intents.size();
            for (int j=0; j<NI; j++) {
                PackageParser.ActivityIntentInfo intent = a.intents.get(j);
                if (SHOW_INFO || Config.LOGV) {
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
            if (SHOW_INFO || Config.LOGV) Log.v(
                TAG, "  " + type + " " +
                (a.info.nonLocalizedLabel != null ? a.info.nonLocalizedLabel : a.info.name) + ":");
            if (SHOW_INFO || Config.LOGV) Log.v(TAG, "    Class=" + a.info.name);
            int NI = a.intents.size();
            for (int j=0; j<NI; j++) {
                PackageParser.ActivityIntentInfo intent = a.intents.get(j);
                if (SHOW_INFO || Config.LOGV) {
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
        protected String packageForFilter(PackageParser.ActivityIntentInfo info) {
            return info.activity.owner.packageName;
        }
        
        @Override
        protected ResolveInfo newResult(PackageParser.ActivityIntentInfo info,
                int match) {
            if (!mSettings.isEnabledLP(info.activity.info, mFlags)) {
                return null;
            }
            final PackageParser.Activity activity = info.activity;
            if (mSafeMode && (activity.info.applicationInfo.flags
                    &ApplicationInfo.FLAG_SYSTEM) == 0) {
                return null;
            }
            final ResolveInfo res = new ResolveInfo();
            res.activityInfo = PackageParser.generateActivityInfo(activity,
                    mFlags);
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
                    out.print(filter.activity.getComponentShortName());
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
        public List queryIntent(Intent intent, String resolvedType, boolean defaultOnly) {
            mFlags = defaultOnly ? PackageManager.MATCH_DEFAULT_ONLY : 0;
            return super.queryIntent(intent, resolvedType, defaultOnly);
        }

        public List queryIntent(Intent intent, String resolvedType, int flags) {
            mFlags = flags;
            return super.queryIntent(intent, resolvedType,
                (flags&PackageManager.MATCH_DEFAULT_ONLY) != 0);
        }

        public List queryIntentForPackage(Intent intent, String resolvedType, int flags,
                                          ArrayList<PackageParser.Service> packageServices) {
            if (packageServices == null) {
                return null;
            }
            mFlags = flags;
            final boolean defaultOnly = (flags&PackageManager.MATCH_DEFAULT_ONLY) != 0;
            int N = packageServices.size();
            ArrayList<ArrayList<PackageParser.ServiceIntentInfo>> listCut =
                new ArrayList<ArrayList<PackageParser.ServiceIntentInfo>>(N);

            ArrayList<PackageParser.ServiceIntentInfo> intentFilters;
            for (int i = 0; i < N; ++i) {
                intentFilters = packageServices.get(i).intents;
                if (intentFilters != null && intentFilters.size() > 0) {
                    listCut.add(intentFilters);
                }
            }
            return super.queryIntentFromList(intent, resolvedType, defaultOnly, listCut);
        }

        public final void addService(PackageParser.Service s) {
            mServices.put(s.getComponentName(), s);
            if (SHOW_INFO || Config.LOGV) Log.v(
                TAG, "  " + (s.info.nonLocalizedLabel != null
                        ? s.info.nonLocalizedLabel : s.info.name) + ":");
            if (SHOW_INFO || Config.LOGV) Log.v(
                    TAG, "    Class=" + s.info.name);
            int NI = s.intents.size();
            int j;
            for (j=0; j<NI; j++) {
                PackageParser.ServiceIntentInfo intent = s.intents.get(j);
                if (SHOW_INFO || Config.LOGV) {
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
            if (SHOW_INFO || Config.LOGV) Log.v(
                TAG, "  " + (s.info.nonLocalizedLabel != null
                        ? s.info.nonLocalizedLabel : s.info.name) + ":");
            if (SHOW_INFO || Config.LOGV) Log.v(
                    TAG, "    Class=" + s.info.name);
            int NI = s.intents.size();
            int j;
            for (j=0; j<NI; j++) {
                PackageParser.ServiceIntentInfo intent = s.intents.get(j);
                if (SHOW_INFO || Config.LOGV) {
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
        protected String packageForFilter(PackageParser.ServiceIntentInfo info) {
            return info.service.owner.packageName;
        }
        
        @Override
        protected ResolveInfo newResult(PackageParser.ServiceIntentInfo filter,
                int match) {
            final PackageParser.ServiceIntentInfo info = (PackageParser.ServiceIntentInfo)filter;
            if (!mSettings.isEnabledLP(info.service.info, mFlags)) {
                return null;
            }
            final PackageParser.Service service = info.service;
            if (mSafeMode && (service.info.applicationInfo.flags
                    &ApplicationInfo.FLAG_SYSTEM) == 0) {
                return null;
            }
            final ResolveInfo res = new ResolveInfo();
            res.serviceInfo = PackageParser.generateServiceInfo(service,
                    mFlags);
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
                    out.print(filter.service.getComponentShortName());
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
            return (v1 > v2) ? -1 : ((v1 < v2) ? 1 : 0);
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

    private static final void sendPackageBroadcast(String action, String pkg,
            Bundle extras, IIntentReceiver finishedReceiver) {
        IActivityManager am = ActivityManagerNative.getDefault();
        if (am != null) {
            try {
                final Intent intent = new Intent(action,
                        pkg != null ? Uri.fromParts("package", pkg, null) : null);
                if (extras != null) {
                    intent.putExtras(extras);
                }
                intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                am.broadcastIntent(null, intent, null, finishedReceiver,
                        0, null, null, null, finishedReceiver != null, false);
            } catch (RemoteException ex) {
            }
        }
    }
    
    public String nextPackageToClean(String lastPackage) {
        synchronized (mPackages) {
            if (!mMediaMounted) {
                // If the external storage is no longer mounted at this point,
                // the caller may not have been able to delete all of this
                // packages files and can not delete any more.  Bail.
                return null;
            }
            if (lastPackage != null) {
                mSettings.mPackagesToBeCleaned.remove(lastPackage);
            }
            return mSettings.mPackagesToBeCleaned.size() > 0
                    ? mSettings.mPackagesToBeCleaned.get(0) : null;
        }
    }

    void schedulePackageCleaning(String packageName) {
        mHandler.sendMessage(mHandler.obtainMessage(START_CLEANING_PACKAGE, packageName));
    }
    
    void startCleaningPackages() {
        synchronized (mPackages) {
            if (!mMediaMounted) {
                return;
            }
            if (mSettings.mPackagesToBeCleaned.size() <= 0) {
                return;
            }
        }
        Intent intent = new Intent(PackageManager.ACTION_CLEAN_EXTERNAL_STORAGE);
        intent.setComponent(DEFAULT_CONTAINER_COMPONENT);
        IActivityManager am = ActivityManagerNative.getDefault();
        if (am != null) {
            try {
                am.startService(null, intent, null);
            } catch (RemoteException e) {
            }
        }
    }
    
    private final class AppDirObserver extends FileObserver {
        public AppDirObserver(String path, int mask, boolean isrom) {
            super(path, mask);
            mRootDir = path;
            mIsRom = isrom;
        }

        public void onEvent(int event, String path) {
            String removedPackage = null;
            int removedUid = -1;
            String addedPackage = null;
            int addedUid = -1;

            synchronized (mInstallLock) {
                String fullPathStr = null;
                File fullPath = null;
                if (path != null) {
                    fullPath = new File(mRootDir, path);
                    fullPathStr = fullPath.getPath();
                }

                if (Config.LOGV) Log.v(
                    TAG, "File " + fullPathStr + " changed: "
                    + Integer.toHexString(event));

                if (!isPackageFilename(path)) {
                    if (Config.LOGV) Log.v(
                        TAG, "Ignoring change of non-package file: " + fullPathStr);
                    return;
                }

                // Ignore packages that are being installed or
                // have just been installed.
                if (ignoreCodePath(fullPathStr)) {
                    return;
                }
                PackageParser.Package p = null;
                synchronized (mPackages) {
                    p = mAppDirs.get(fullPathStr);
                }
                if ((event&REMOVE_EVENTS) != 0) {
                    if (p != null) {
                        removePackageLI(p, true);
                        removedPackage = p.applicationInfo.packageName;
                        removedUid = p.applicationInfo.uid;
                    }
                }

                if ((event&ADD_EVENTS) != 0) {
                    if (p == null) {
                        p = scanPackageLI(fullPath,
                                (mIsRom ? PackageParser.PARSE_IS_SYSTEM
                                        | PackageParser.PARSE_IS_SYSTEM_DIR: 0) |
                                PackageParser.PARSE_CHATTY |
                                PackageParser.PARSE_MUST_BE_APK,
                                SCAN_MONITOR | SCAN_NO_PATHS);
                        if (p != null) {
                            synchronized (mPackages) {
                                updatePermissionsLP(p.packageName, p,
                                        p.permissions.size() > 0, false, false);
                            }
                            addedPackage = p.applicationInfo.packageName;
                            addedUid = p.applicationInfo.uid;
                        }
                    }
                }

                synchronized (mPackages) {
                    mSettings.writeLP();
                }
            }

            if (removedPackage != null) {
                Bundle extras = new Bundle(1);
                extras.putInt(Intent.EXTRA_UID, removedUid);
                extras.putBoolean(Intent.EXTRA_DATA_REMOVED, false);
                sendPackageBroadcast(Intent.ACTION_PACKAGE_REMOVED, removedPackage,
                        extras, null);
            }
            if (addedPackage != null) {
                Bundle extras = new Bundle(1);
                extras.putInt(Intent.EXTRA_UID, addedUid);
                sendPackageBroadcast(Intent.ACTION_PACKAGE_ADDED, addedPackage,
                        extras, null);
            }
        }

        private final String mRootDir;
        private final boolean mIsRom;
    }

    /* Called when a downloaded package installation has been confirmed by the user */
    public void installPackage(
            final Uri packageURI, final IPackageInstallObserver observer, final int flags) {
        installPackage(packageURI, observer, flags, null);
    }

    /* Called when a downloaded package installation has been confirmed by the user */
    public void installPackage(
            final Uri packageURI, final IPackageInstallObserver observer, final int flags,
            final String installerPackageName) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.INSTALL_PACKAGES, null);

        Message msg = mHandler.obtainMessage(INIT_COPY);
        msg.obj = new InstallParams(packageURI, observer, flags,
                installerPackageName);
        mHandler.sendMessage(msg);
    }

    public void finishPackageInstall(int token) {
        if (DEBUG_INSTALL) Log.v(TAG, "BM finishing package install for " + token);
        Message msg = mHandler.obtainMessage(POST_INSTALL, token, 0);
        mHandler.sendMessage(msg);
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
                    args.doPostInstall(res.returnCode);
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

    abstract class HandlerParams {
        final static int MAX_RETRIES = 4;
        int retry = 0;
        final void startCopy() {
            try {
                if (DEBUG_SD_INSTALL) Log.i(TAG, "startCopy");
                retry++;
                if (retry > MAX_RETRIES) {
                    Slog.w(TAG, "Failed to invoke remote methods on default container service. Giving up");
                    mHandler.sendEmptyMessage(MCS_GIVE_UP);
                    handleServiceError();
                    return;
                } else {
                    handleStartCopy();
                    if (DEBUG_SD_INSTALL) Log.i(TAG, "Posting install MCS_UNBIND");
                    mHandler.sendEmptyMessage(MCS_UNBIND);
                }
            } catch (RemoteException e) {
                if (DEBUG_SD_INSTALL) Log.i(TAG, "Posting install MCS_RECONNECT");
                mHandler.sendEmptyMessage(MCS_RECONNECT);
            }
            handleReturnCode();
        }

        final void serviceError() {
            if (DEBUG_SD_INSTALL) Log.i(TAG, "serviceError");
            handleServiceError();
            handleReturnCode();
        }
        abstract void handleStartCopy() throws RemoteException;
        abstract void handleServiceError();
        abstract void handleReturnCode();
    }

    class InstallParams extends HandlerParams {
        final IPackageInstallObserver observer;
        int flags;
        final Uri packageURI;
        final String installerPackageName;
        private InstallArgs mArgs;
        private int mRet;
        InstallParams(Uri packageURI,
                IPackageInstallObserver observer, int flags,
                String installerPackageName) {
            this.packageURI = packageURI;
            this.flags = flags;
            this.observer = observer;
            this.installerPackageName = installerPackageName;
        }

        private int installLocationPolicy(PackageInfoLite pkgLite, int flags) {
            String packageName = pkgLite.packageName;
            int installLocation = pkgLite.installLocation;
            boolean onSd = (flags & PackageManager.INSTALL_EXTERNAL) != 0;
            synchronized (mPackages) {
                PackageParser.Package pkg = mPackages.get(packageName);
                if (pkg != null) {
                    if ((flags & PackageManager.INSTALL_REPLACE_EXISTING) != 0) {
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
                                if ((pkg.applicationInfo.flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0) {
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
            boolean fwdLocked = (flags & PackageManager.INSTALL_FORWARD_LOCK) != 0;
            boolean onSd = (flags & PackageManager.INSTALL_EXTERNAL) != 0;
            boolean onInt = (flags & PackageManager.INSTALL_INTERNAL) != 0;
            if (onInt && onSd) {
                // Check if both bits are set.
                Slog.w(TAG, "Conflicting flags specified for installing on both internal and external");
                ret = PackageManager.INSTALL_FAILED_INVALID_INSTALL_LOCATION;
            } else if (fwdLocked && onSd) {
                // Check for forward locked apps
                Slog.w(TAG, "Cannot install fwd locked apps on sdcard");
                ret = PackageManager.INSTALL_FAILED_INVALID_INSTALL_LOCATION;
            } else {
                // Remote call to find out default install location
                PackageInfoLite pkgLite = mContainerService.getMinimalPackageInfo(packageURI, flags);
                int loc = pkgLite.recommendedInstallLocation;
                if (loc == PackageHelper.RECOMMEND_FAILED_INVALID_LOCATION){
                    ret = PackageManager.INSTALL_FAILED_INVALID_INSTALL_LOCATION;
                } else if (loc == PackageHelper.RECOMMEND_FAILED_ALREADY_EXISTS){
                    ret = PackageManager.INSTALL_FAILED_ALREADY_EXISTS;
                } else if (loc == PackageHelper.RECOMMEND_FAILED_INSUFFICIENT_STORAGE){
                    ret = PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE;
                } else if (loc == PackageHelper.RECOMMEND_FAILED_INVALID_APK) {
                    ret = PackageManager.INSTALL_FAILED_INVALID_APK;
                } else if (loc == PackageHelper.RECOMMEND_MEDIA_UNAVAILABLE) {
                  ret = PackageManager.INSTALL_FAILED_MEDIA_UNAVAILABLE;
                } else {
                    // Override with defaults if needed.
                    loc = installLocationPolicy(pkgLite, flags);
                    if (!onSd && !onInt) {
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
            // Create the file args now.
            mArgs = createInstallArgs(this);
            if (ret == PackageManager.INSTALL_SUCCEEDED) {
                // Create copy only if we are not in an erroneous state.
                // Remote call to initiate copy using temporary file
                ret = mArgs.copyApk(mContainerService, true);
            }
            mRet = ret;
        }

        @Override
        void handleReturnCode() {
            processPendingInstall(mArgs, mRet);
        }

        @Override
        void handleServiceError() {
            mArgs = createInstallArgs(this);
            mRet = PackageManager.INSTALL_FAILED_INTERNAL_ERROR;
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
        int mRet;
        MoveParams(InstallArgs srcArgs,
                IPackageMoveObserver observer,
                int flags, String packageName) {
            this.srcArgs = srcArgs;
            this.observer = observer;
            this.flags = flags;
            this.packageName = packageName;
            if (srcArgs != null) {
                Uri packageUri = Uri.fromFile(new File(srcArgs.getCodePath()));
                targetArgs = createInstallArgs(packageUri, flags, packageName);
            } else {
                targetArgs = null;
            }
        }

        public void handleStartCopy() throws RemoteException {
            mRet = PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE;
            // Check for storage space on target medium
            if (!targetArgs.checkFreeStorage(mContainerService)) {
                Log.w(TAG, "Insufficient storage to install");
                return;
            }
            // Create the file args now.
            mRet = targetArgs.copyApk(mContainerService, false);
            targetArgs.doPreInstall(mRet);
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
            targetArgs.doPostInstall(mRet);
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

    private InstallArgs createInstallArgs(InstallParams params) {
        if (installOnSd(params.flags)) {
            return new SdInstallArgs(params);
        } else {
            return new FileInstallArgs(params);
        }
    }

    private InstallArgs createInstallArgs(int flags, String fullCodePath, String fullResourcePath) {
        if (installOnSd(flags)) {
            return new SdInstallArgs(fullCodePath, fullResourcePath);
        } else {
            return new FileInstallArgs(fullCodePath, fullResourcePath);
        }
    }

    private InstallArgs createInstallArgs(Uri packageURI, int flags,
            String pkgName) {
        if (installOnSd(flags)) {
            String cid = getNextCodePath(null, pkgName, "/" + SdInstallArgs.RES_FILE_NAME);
            return new SdInstallArgs(packageURI, cid);
        } else {
            return new FileInstallArgs(packageURI, pkgName);
        }
    }

    static abstract class InstallArgs {
        final IPackageInstallObserver observer;
        // Always refers to PackageManager flags only
        final int flags;
        final Uri packageURI;
        final String installerPackageName;

        InstallArgs(Uri packageURI,
                IPackageInstallObserver observer, int flags,
                String installerPackageName) {
            this.packageURI = packageURI;
            this.flags = flags;
            this.observer = observer;
            this.installerPackageName = installerPackageName;
        }

        abstract void createCopyFile();
        abstract int copyApk(IMediaContainerService imcs, boolean temp) throws RemoteException;
        abstract int doPreInstall(int status);
        abstract boolean doRename(int status, String pkgName, String oldCodePath);
        abstract int doPostInstall(int status);
        abstract String getCodePath();
        abstract String getResourcePath();
        // Need installer lock especially for dex file removal.
        abstract void cleanUpResourcesLI();
        abstract boolean doPostDeleteLI(boolean delete);
        abstract boolean checkFreeStorage(IMediaContainerService imcs) throws RemoteException;
    }

    class FileInstallArgs extends InstallArgs {
        File installDir;
        String codeFileName;
        String resourceFileName;
        boolean created = false;

        FileInstallArgs(InstallParams params) {
            super(params.packageURI, params.observer,
                    params.flags, params.installerPackageName);
        }

        FileInstallArgs(String fullCodePath, String fullResourcePath) {
            super(null, null, 0, null);
            File codeFile = new File(fullCodePath);
            installDir = codeFile.getParentFile();
            codeFileName = fullCodePath;
            resourceFileName = fullResourcePath;
        }

        FileInstallArgs(Uri packageURI, String pkgName) {
            super(packageURI, null, 0, null);
            boolean fwdLocked = isFwdLocked(flags);
            installDir = fwdLocked ? mDrmAppPrivateInstallDir : mAppInstallDir;
            String apkName = getNextCodePath(null, pkgName, ".apk");
            codeFileName = new File(installDir, apkName + ".apk").getPath();
            resourceFileName = getResourcePathFromCodePath();
        }

        boolean  checkFreeStorage(IMediaContainerService imcs) throws RemoteException {
            return imcs.checkFreeStorage(false, packageURI);
        }

        String getCodePath() {
            return codeFileName;
        }

        void createCopyFile() {
            boolean fwdLocked = isFwdLocked(flags);
            installDir = fwdLocked ? mDrmAppPrivateInstallDir : mAppInstallDir;
            codeFileName = createTempPackageFile(installDir).getPath();
            resourceFileName = getResourcePathFromCodePath();
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
            out = ParcelFileDescriptor.open(codeFile,
                    ParcelFileDescriptor.MODE_READ_WRITE);
            } catch (FileNotFoundException e) {
                Slog.e(TAG, "Failed to create file descritpor for : " + codeFileName);
                return PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE;
            }
            // Copy the resource now
            int ret = PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE;
            try {
                if (imcs.copyResource(packageURI, out)) {
                    ret = PackageManager.INSTALL_SUCCEEDED;
                }
            } finally {
                try { if (out != null) out.close(); } catch (IOException e) {}
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
                // Rename based on packageName
                File codeFile = new File(getCodePath());
                String apkName = getNextCodePath(oldCodePath, pkgName, ".apk");
                File desFile = new File(installDir, apkName + ".apk");
                if (!codeFile.renameTo(desFile)) {
                    return false;
                }
                // Reset paths since the file has been renamed.
                codeFileName = desFile.getPath();
                resourceFileName = getResourcePathFromCodePath();
                // Set permissions
                if (!setPermissions()) {
                    // Failed setting permissions.
                    return false;
                }
                return true;
            }
        }

        int doPostInstall(int status) {
            if (status != PackageManager.INSTALL_SUCCEEDED) {
                cleanUp();
            }
            return status;
        }

        String getResourcePath() {
            return resourceFileName;
        }

        String getResourcePathFromCodePath() {
            String codePath = getCodePath();
            if ((flags & PackageManager.INSTALL_FORWARD_LOCK) != 0) {
                String apkNameOnly = getApkName(codePath);
                return mAppInstallDir.getPath() + "/" + apkNameOnly + ".zip";
            } else {
                return codePath;
            }
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
            return ret;
        }

        void cleanUpResourcesLI() {
            String sourceDir = getCodePath();
            if (cleanUp() && mInstaller != null) {
                int retCode = mInstaller.rmdex(sourceDir);
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
            if (!isFwdLocked(flags)) {
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
            cleanUpResourcesLI();
            return true;
        }
    }

    class SdInstallArgs extends InstallArgs {
        String cid;
        String cachePath;
        static final String RES_FILE_NAME = "pkg.apk";

        SdInstallArgs(InstallParams params) {
            super(params.packageURI, params.observer,
                    params.flags, params.installerPackageName);
        }

        SdInstallArgs(String fullCodePath, String fullResourcePath) {
            super(null, null, PackageManager.INSTALL_EXTERNAL, null);
            // Extract cid from fullCodePath
            int eidx = fullCodePath.lastIndexOf("/");
            String subStr1 = fullCodePath.substring(0, eidx);
            int sidx = subStr1.lastIndexOf("/");
            cid = subStr1.substring(sidx+1, eidx);
            cachePath = subStr1;
        }

        SdInstallArgs(String cid) {
            super(null, null, PackageManager.INSTALL_EXTERNAL, null);
            this.cid = cid;
            cachePath = PackageHelper.getSdDir(cid);
        }

        SdInstallArgs(Uri packageURI, String cid) {
            super(packageURI, null, PackageManager.INSTALL_EXTERNAL, null);
            this.cid = cid;
        }

        void createCopyFile() {
            cid = getTempContainerId();
        }

        boolean  checkFreeStorage(IMediaContainerService imcs) throws RemoteException {
            return imcs.checkFreeStorage(true, packageURI);
        }

        int copyApk(IMediaContainerService imcs, boolean temp) throws RemoteException {
            if (temp) {
                createCopyFile();
            }
            cachePath = imcs.copyResourceToContainer(
                    packageURI, cid,
                    getEncryptKey(), RES_FILE_NAME);
            return (cachePath == null) ? PackageManager.INSTALL_FAILED_CONTAINER_ERROR :
                PackageManager.INSTALL_SUCCEEDED;
        }

        @Override
        String getCodePath() {
            return cachePath + "/" + RES_FILE_NAME;
        }

        @Override
        String getResourcePath() {
            return cachePath + "/" + RES_FILE_NAME;
        }

        int doPreInstall(int status) {
            if (status != PackageManager.INSTALL_SUCCEEDED) {
                // Destroy container
                PackageHelper.destroySdDir(cid);
            } else {
                boolean mounted = PackageHelper.isContainerMounted(cid);
                if (!mounted) {
                    cachePath = PackageHelper.mountSdDir(cid, getEncryptKey(), Process.SYSTEM_UID);
                    if (cachePath == null) {
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
                    " at path: " + cachePath + " to " + newCacheId +
                    " at new path: " + newCachePath);
            cid = newCacheId;
            cachePath = newCachePath;
            return true;
        }

        int doPostInstall(int status) {
            if (status != PackageManager.INSTALL_SUCCEEDED) {
                cleanUp();
            } else {
                boolean mounted = PackageHelper.isContainerMounted(cid);
                if (!mounted) {
                    PackageHelper.mountSdDir(cid,
                            getEncryptKey(), Process.myUid());
                }
            }
            return status;
        }

        private void cleanUp() {
            // Destroy secure container
            PackageHelper.destroySdDir(cid);
        }

        void cleanUpResourcesLI() {
            String sourceFile = getCodePath();
            // Remove dex file
            if (mInstaller != null) {
                int retCode = mInstaller.rmdex(sourceFile);
                if (retCode < 0) {
                    Slog.w(TAG, "Couldn't remove dex file for package: "
                            + " at location "
                            + sourceFile.toString() + ", retcode=" + retCode);
                    // we don't consider this to be a failure of the core package deletion
                }
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
            int idx = cid.lastIndexOf("-");
            if (idx == -1) {
                return cid;
            }
            return cid.substring(0, idx);
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
    };

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
        PackageParser.Package pkg;
        int returnCode;
        PackageRemovedInfo removedInfo;
    }

    /*
     * Install a non-existing package.
     */
    private void installNewPackageLI(PackageParser.Package pkg,
            int parseFlags,
            int scanMode,
            String installerPackageName, PackageInstalledInfo res) {
        // Remember this for later, in case we need to rollback this install
        String pkgName = pkg.packageName;

        boolean dataDirExists = getDataPathForPackage(pkg).exists();
        res.name = pkgName;
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
        PackageParser.Package newPackage = scanPackageLI(pkg, parseFlags, scanMode);
        if (newPackage == null) {
            Slog.w(TAG, "Package couldn't be installed in " + pkg.mPath);
            if ((res.returnCode=mLastScanError) == PackageManager.INSTALL_SUCCEEDED) {
                res.returnCode = PackageManager.INSTALL_FAILED_INVALID_APK;
            }
        } else {
            updateSettingsLI(newPackage,
                    installerPackageName,
                    res);
            // delete the partially installed application. the data directory will have to be
            // restored if it was already existing
            if (res.returnCode != PackageManager.INSTALL_SUCCEEDED) {
                // remove package from internal structures.  Note that we want deletePackageX to
                // delete the package data and cache directories that it created in
                // scanPackageLocked, unless those directories existed before we even tried to
                // install.
                deletePackageLI(
                        pkgName, false,
                        dataDirExists ? PackageManager.DONT_DELETE_DATA : 0,
                                res.removedInfo);
            }
        }
    }

    private void replacePackageLI(PackageParser.Package pkg,
            int parseFlags,
            int scanMode,
            String installerPackageName, PackageInstalledInfo res) {

        PackageParser.Package oldPackage;
        String pkgName = pkg.packageName;
        // First find the old package info and check signatures
        synchronized(mPackages) {
            oldPackage = mPackages.get(pkgName);
            if (checkSignaturesLP(oldPackage.mSignatures, pkg.mSignatures)
                    != PackageManager.SIGNATURE_MATCH) {
                res.returnCode = PackageManager.INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES;
                return;
            }
        }
        boolean sysPkg = ((oldPackage.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
        if (sysPkg) {
            replaceSystemPackageLI(oldPackage, pkg, parseFlags, scanMode, installerPackageName, res);
        } else {
            replaceNonSystemPackageLI(oldPackage, pkg, parseFlags, scanMode, installerPackageName, res);
        }
    }

    private void replaceNonSystemPackageLI(PackageParser.Package deletedPackage,
            PackageParser.Package pkg,
            int parseFlags, int scanMode,
            String installerPackageName, PackageInstalledInfo res) {
        PackageParser.Package newPackage = null;
        String pkgName = deletedPackage.packageName;
        boolean deletedPkg = true;
        boolean updatedSettings = false;

        String oldInstallerPackageName = null;
        synchronized (mPackages) {
            oldInstallerPackageName = mSettings.getInstallerPackageName(pkgName);
        }

        // First delete the existing package while retaining the data directory
        if (!deletePackageLI(pkgName, true, PackageManager.DONT_DELETE_DATA,
                res.removedInfo)) {
            // If the existing package was'nt successfully deleted
            res.returnCode = PackageManager.INSTALL_FAILED_REPLACE_COULDNT_DELETE;
            deletedPkg = false;
        } else {
            // Successfully deleted the old package. Now proceed with re-installation
            mLastScanError = PackageManager.INSTALL_SUCCEEDED;
            newPackage = scanPackageLI(pkg, parseFlags, scanMode);
            if (newPackage == null) {
                Slog.w(TAG, "Package couldn't be installed in " + pkg.mPath);
                if ((res.returnCode=mLastScanError) == PackageManager.INSTALL_SUCCEEDED) {
                    res.returnCode = PackageManager.INSTALL_FAILED_INVALID_APK;
                }
            } else {
                updateSettingsLI(newPackage,
                        installerPackageName,
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
                deletePackageLI(
                        pkgName, true,
                        PackageManager.DONT_DELETE_DATA,
                                res.removedInfo);
            }
            // Since we failed to install the new package we need to restore the old
            // package that we deleted.
            if(deletedPkg) {
                File restoreFile = new File(deletedPackage.mPath);
                if (restoreFile == null) {
                    Slog.e(TAG, "Failed allocating storage when restoring pkg : " + pkgName);
                    return;
                }
                // Parse old package
                boolean oldOnSd = isExternal(deletedPackage);
                int oldParseFlags  = mDefParseFlags | PackageParser.PARSE_CHATTY |
                        (isForwardLocked(deletedPackage) ? PackageParser.PARSE_FORWARD_LOCK : 0) |
                        (oldOnSd ? PackageParser.PARSE_ON_SDCARD : 0);
                int oldScanMode = (oldOnSd ? 0 : SCAN_MONITOR) | SCAN_UPDATE_SIGNATURE;
                if (scanPackageLI(restoreFile, oldParseFlags, oldScanMode) == null) {
                    Slog.e(TAG, "Failed to restore package : " + pkgName + " after failed upgrade");
                    return;
                }
                // Restore of old package succeeded. Update permissions.
                synchronized (mPackages) {
                    updatePermissionsLP(deletedPackage.packageName, deletedPackage,
                            true, false, false);
                    mSettings.writeLP();
                }
                Slog.i(TAG, "Successfully restored package : " + pkgName + " after failed upgrade");
            }
        }
    }

    private void replaceSystemPackageLI(PackageParser.Package deletedPackage,
            PackageParser.Package pkg,
            int parseFlags, int scanMode,
            String installerPackageName, PackageInstalledInfo res) {
        PackageParser.Package newPackage = null;
        boolean updatedSettings = false;
        parseFlags |= PackageManager.INSTALL_REPLACE_EXISTING |
                PackageParser.PARSE_IS_SYSTEM;
        String packageName = deletedPackage.packageName;
        res.returnCode = PackageManager.INSTALL_FAILED_REPLACE_COULDNT_DELETE;
        if (packageName == null) {
            Slog.w(TAG, "Attempt to delete null packageName.");
            return;
        }
        PackageParser.Package oldPkg;
        PackageSetting oldPkgSetting;
        synchronized (mPackages) {
            oldPkg = mPackages.get(packageName);
            oldPkgSetting = mSettings.mPackages.get(packageName);
            if((oldPkg == null) || (oldPkg.applicationInfo == null) ||
                    (oldPkgSetting == null)) {
                Slog.w(TAG, "Couldn't find package:"+packageName+" information");
                return;
            }
        }
        res.removedInfo.uid = oldPkg.applicationInfo.uid;
        res.removedInfo.removedPackage = packageName;
        // Remove existing system package
        removePackageLI(oldPkg, true);
        synchronized (mPackages) {
            res.removedInfo.removedUid = mSettings.disableSystemPackageLP(packageName);
        }

        // Successfully disabled the old package. Now proceed with re-installation
        mLastScanError = PackageManager.INSTALL_SUCCEEDED;
        pkg.applicationInfo.flags |= ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
        newPackage = scanPackageLI(pkg, parseFlags, scanMode);
        if (newPackage == null) {
            Slog.w(TAG, "Package couldn't be installed in " + pkg.mPath);
            if ((res.returnCode=mLastScanError) == PackageManager.INSTALL_SUCCEEDED) {
                res.returnCode = PackageManager.INSTALL_FAILED_INVALID_APK;
            }
        } else {
            updateSettingsLI(newPackage, installerPackageName, res);
            updatedSettings = true;
        }

        if (res.returnCode != PackageManager.INSTALL_SUCCEEDED) {
            // Re installation failed. Restore old information
            // Remove new pkg information
            if (newPackage != null) {
                removePackageLI(newPackage, true);
            }
            // Add back the old system package
            scanPackageLI(oldPkg, parseFlags,
                    SCAN_MONITOR
                    | SCAN_UPDATE_SIGNATURE);
            // Restore the old system information in Settings
            synchronized(mPackages) {
                if(updatedSettings) {
                    mSettings.enableSystemPackageLP(packageName);
                    mSettings.setInstallerPackageName(packageName,
                            oldPkgSetting.installerPackageName);
                }
                mSettings.writeLP();
            }
        } else {
            // If this is an update to an existing update, setup 
            // to remove the existing update.
            synchronized (mPackages) {
                PackageSetting ps = mSettings.getDisabledSystemPkg(packageName);
                if (ps != null && ps.codePathString != null &&
                        !ps.codePathString.equals(oldPkgSetting.codePathString)) {
                    int installFlags = 0;
                    res.removedInfo.args = createInstallArgs(0, oldPkgSetting.codePathString,
                            oldPkgSetting.resourcePathString);
                }
            }
        }
    }

    // Utility method used to move dex files during install.
    private int moveDexFilesLI(PackageParser.Package newPackage) {
        int retCode;
        if ((newPackage.applicationInfo.flags&ApplicationInfo.FLAG_HAS_CODE) != 0) {
            retCode = mInstaller.movedex(newPackage.mScanPath, newPackage.mPath);
            if (retCode != 0) {
                Slog.e(TAG, "Couldn't rename dex file: " + newPackage.mPath);
                return PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE;
            }
        }
        return PackageManager.INSTALL_SUCCEEDED;
    }

    private void updateSettingsLI(PackageParser.Package newPackage,
            String installerPackageName, PackageInstalledInfo res) {
        String pkgName = newPackage.packageName;
        synchronized (mPackages) {
            //write settings. the installStatus will be incomplete at this stage.
            //note that the new package setting would have already been
            //added to mPackages. It hasn't been persisted yet.
            mSettings.setInstallStatus(pkgName, PKG_INSTALL_INCOMPLETE);
            mSettings.writeLP();
        }

        if ((res.returnCode = moveDexFilesLI(newPackage))
                != PackageManager.INSTALL_SUCCEEDED) {
            // Discontinue if moving dex files failed.
            return;
        }
        if((res.returnCode = setPermissionsLI(newPackage))
                != PackageManager.INSTALL_SUCCEEDED) {
            if (mInstaller != null) {
                mInstaller.rmdex(newPackage.mScanPath);
            }
            return;
        } else {
            Log.d(TAG, "New package installed in " + newPackage.mPath);
        }
        synchronized (mPackages) {
            updatePermissionsLP(newPackage.packageName, newPackage,
                    newPackage.permissions.size() > 0, true, false);
            res.name = pkgName;
            res.uid = newPackage.applicationInfo.uid;
            res.pkg = newPackage;
            mSettings.setInstallStatus(pkgName, PKG_INSTALL_COMPLETE);
            mSettings.setInstallerPackageName(pkgName, installerPackageName);
            res.returnCode = PackageManager.INSTALL_SUCCEEDED;
            //to update install status
            mSettings.writeLP();
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

        // Retrieve PackageSettings and parse package
        int parseFlags = PackageParser.PARSE_CHATTY |
        (forwardLocked ? PackageParser.PARSE_FORWARD_LOCK : 0) |
        (onSd ? PackageParser.PARSE_ON_SDCARD : 0);
        parseFlags |= mDefParseFlags;
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
                } else if (mPackages.containsKey(pkgName)) {
                    // This package, under its official name, already exists
                    // on the device; we should replace it.
                    replace = true;
                }
            }
            PackageSetting ps = mSettings.mPackages.get(pkgName);
            if (ps != null) {
                oldCodePath = mSettings.mPackages.get(pkgName).codePathString;
                if (ps.pkg != null && ps.pkg.applicationInfo != null) {
                    systemApp = (ps.pkg.applicationInfo.flags &
                            ApplicationInfo.FLAG_SYSTEM) != 0;
                }
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
        if (replace) {
            replacePackageLI(pkg, parseFlags, scanMode,
                    installerPackageName, res);
        } else {
            installNewPackageLI(pkg, parseFlags, scanMode,
                    installerPackageName,res);
        }
    }

    private int setPermissionsLI(PackageParser.Package newPackage) {
        String pkgName = newPackage.packageName;
        int retCode = 0;
        // TODO Gross hack but fix later. Ideally move this to be a post installation
        // check after alloting uid.
        if ((newPackage.applicationInfo.flags
                & ApplicationInfo.FLAG_FORWARD_LOCK) != 0) {
            File destResourceFile = new File(newPackage.applicationInfo.publicSourceDir);
            try {
                extractPublicFiles(newPackage, destResourceFile);
            } catch (IOException e) {
                Slog.e(TAG, "Couldn't create a new zip file for the public parts of a" +
                           " forward-locked app.");
                return PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE;
            } finally {
                //TODO clean up the extracted public files
            }
            if (mInstaller != null) {
                retCode = mInstaller.setForwardLockPerm(getApkName(newPackage.mPath),
                        newPackage.applicationInfo.uid);
            } else {
                final int filePermissions =
                        FileUtils.S_IRUSR|FileUtils.S_IWUSR|FileUtils.S_IRGRP;
                retCode = FileUtils.setPermissions(newPackage.mPath, filePermissions, -1,
                                                   newPackage.applicationInfo.uid);
            }
        } else {
            // The permissions on the resource file was set when it was copied for
            // non forward locked apps and apps on sdcard
        }

        if (retCode != 0) {
            Slog.e(TAG, "Couldn't set new package file permissions for " +
                    newPackage.mPath
                       + ". The return code was: " + retCode);
            // TODO Define new internal error
            return PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE;
        }
        return PackageManager.INSTALL_SUCCEEDED;
    }

    private boolean isForwardLocked(PackageParser.Package pkg) {
        return  ((pkg.applicationInfo.flags & ApplicationInfo.FLAG_FORWARD_LOCK) != 0);
    }

    private boolean isExternal(PackageParser.Package pkg) {
        return  ((pkg.applicationInfo.flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0);
    }

    private void extractPublicFiles(PackageParser.Package newPackage,
                                    File publicZipFile) throws IOException {
        final ZipOutputStream publicZipOutStream =
                new ZipOutputStream(new FileOutputStream(publicZipFile));
        final ZipFile privateZip = new ZipFile(newPackage.mPath);

        // Copy manifest, resources.arsc and res directory to public zip

        final Enumeration<? extends ZipEntry> privateZipEntries = privateZip.entries();
        while (privateZipEntries.hasMoreElements()) {
            final ZipEntry zipEntry = privateZipEntries.nextElement();
            final String zipEntryName = zipEntry.getName();
            if ("AndroidManifest.xml".equals(zipEntryName)
                || "resources.arsc".equals(zipEntryName)
                || zipEntryName.startsWith("res/")) {
                try {
                    copyZipEntry(zipEntry, privateZip, publicZipOutStream);
                } catch (IOException e) {
                    try {
                        publicZipOutStream.close();
                        throw e;
                    } finally {
                        publicZipFile.delete();
                    }
                }
            }
        }

        publicZipOutStream.close();
        FileUtils.setPermissions(
                publicZipFile.getAbsolutePath(),
                FileUtils.S_IRUSR|FileUtils.S_IWUSR|FileUtils.S_IRGRP|FileUtils.S_IROTH,
                -1, -1);
    }

    private static void copyZipEntry(ZipEntry zipEntry,
                                     ZipFile inZipFile,
                                     ZipOutputStream outZipStream) throws IOException {
        byte[] buffer = new byte[4096];
        int num;

        ZipEntry newEntry;
        if (zipEntry.getMethod() == ZipEntry.STORED) {
            // Preserve the STORED method of the input entry.
            newEntry = new ZipEntry(zipEntry);
        } else {
            // Create a new entry so that the compressed len is recomputed.
            newEntry = new ZipEntry(zipEntry.getName());
        }
        outZipStream.putNextEntry(newEntry);

        InputStream data = inZipFile.getInputStream(zipEntry);
        while ((num = data.read(buffer)) > 0) {
            outZipStream.write(buffer, 0, num);
        }
        outZipStream.flush();
    }

    private void deleteTempPackageFiles() {
        FilenameFilter filter = new FilenameFilter() {
            public boolean accept(File dir, String name) {
                return name.startsWith("vmdl") && name.endsWith(".tmp");
            }
        };
        String tmpFilesList[] = mAppInstallDir.list(filter);
        if(tmpFilesList == null) {
            return;
        }
        for(int i = 0; i < tmpFilesList.length; i++) {
            File tmpFile = new File(mAppInstallDir, tmpFilesList[i]);
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
        } catch (IOException e) {
            Slog.e(TAG, "Trouble getting the canoncical path for a temp file.");
            return null;
        }
        return tmpPackageFile;
    }

    public void deletePackage(final String packageName,
                              final IPackageDeleteObserver observer,
                              final int flags) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.DELETE_PACKAGES, null);
        // Queue up an async operation since the package deletion may take a little while.
        mHandler.post(new Runnable() {
            public void run() {
                mHandler.removeCallbacks(this);
                final boolean succeded = deletePackageX(packageName, true, true, flags);
                if (observer != null) {
                    try {
                        observer.packageDeleted(succeded);
                    } catch (RemoteException e) {
                        Log.i(TAG, "Observer no longer exists.");
                    } //end catch
                } //end if
            } //end run
        });
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
    private boolean deletePackageX(String packageName, boolean sendBroadCast,
                                   boolean deleteCodeAndResources, int flags) {
        PackageRemovedInfo info = new PackageRemovedInfo();
        boolean res;

        IDevicePolicyManager dpm = IDevicePolicyManager.Stub.asInterface(
                ServiceManager.getService(Context.DEVICE_POLICY_SERVICE));
        try {
            if (dpm != null && dpm.packageHasActiveAdmins(packageName)) {
                Slog.w(TAG, "Not removing package " + packageName + ": has active device admin");
                return false;
            }
        } catch (RemoteException e) {
        }
        
        synchronized (mInstallLock) {
            res = deletePackageLI(packageName, deleteCodeAndResources,
                    flags | REMOVE_CHATTY, info);
        }

        if(res && sendBroadCast) {
            boolean systemUpdate = info.isRemovedPackageSystemUpdate;
            info.sendBroadcast(deleteCodeAndResources, systemUpdate);

            // If the removed package was a system update, the old system packaged
            // was re-enabled; we need to broadcast this information
            if (systemUpdate) {
                Bundle extras = new Bundle(1);
                extras.putInt(Intent.EXTRA_UID, info.removedUid >= 0 ? info.removedUid : info.uid);
                extras.putBoolean(Intent.EXTRA_REPLACING, true);

                sendPackageBroadcast(Intent.ACTION_PACKAGE_ADDED, packageName, extras, null);
                sendPackageBroadcast(Intent.ACTION_PACKAGE_REPLACED, packageName, extras, null);
            }
        }
        // Force a gc here.
        Runtime.getRuntime().gc();
        // Delete the resources here after sending the broadcast to let
        // other processes clean up before deleting resources.
        if (info.args != null) {
            synchronized (mInstallLock) {
                info.args.doPostDeleteLI(deleteCodeAndResources);
            }
        }
        return res;
    }

    static class PackageRemovedInfo {
        String removedPackage;
        int uid = -1;
        int removedUid = -1;
        boolean isRemovedPackageSystemUpdate = false;
        // Clean up resources deleted packages.
        InstallArgs args = null;

        void sendBroadcast(boolean fullRemove, boolean replacing) {
            Bundle extras = new Bundle(1);
            extras.putInt(Intent.EXTRA_UID, removedUid >= 0 ? removedUid : uid);
            extras.putBoolean(Intent.EXTRA_DATA_REMOVED, fullRemove);
            if (replacing) {
                extras.putBoolean(Intent.EXTRA_REPLACING, true);
            }
            if (removedPackage != null) {
                sendPackageBroadcast(Intent.ACTION_PACKAGE_REMOVED, removedPackage, extras, null);
            }
            if (removedUid >= 0) {
                sendPackageBroadcast(Intent.ACTION_UID_REMOVED, null, extras, null);
            }
        }
    }

    /*
     * This method deletes the package from internal data structures. If the DONT_DELETE_DATA
     * flag is not set, the data directory is removed as well.
     * make sure this flag is set for partially installed apps. If not its meaningless to
     * delete a partially installed application.
     */
    private void removePackageDataLI(PackageParser.Package p, PackageRemovedInfo outInfo,
            int flags) {
        String packageName = p.packageName;
        if (outInfo != null) {
            outInfo.removedPackage = packageName;
        }
        removePackageLI(p, (flags&REMOVE_CHATTY) != 0);
        // Retrieve object to delete permissions for shared user later on
        PackageSetting deletedPs;
        synchronized (mPackages) {
            deletedPs = mSettings.mPackages.get(packageName);
        }
        if ((flags&PackageManager.DONT_DELETE_DATA) == 0) {
            if (mInstaller != null) {
                int retCode = mInstaller.remove(packageName);
                if (retCode < 0) {
                    Slog.w(TAG, "Couldn't remove app data or cache directory for package: "
                               + packageName + ", retcode=" + retCode);
                    // we don't consider this to be a failure of the core package deletion
                }
            } else {
                //for emulator
                PackageParser.Package pkg = mPackages.get(packageName);
                File dataDir = new File(pkg.applicationInfo.dataDir);
                dataDir.delete();
            }
            schedulePackageCleaning(packageName);
        }
        synchronized (mPackages) {
            if (deletedPs != null) {
                if ((flags&PackageManager.DONT_DELETE_DATA) == 0) {
                    if (outInfo != null) {
                        outInfo.removedUid = mSettings.removePackageLP(packageName);
                    }
                    if (deletedPs != null) {
                        updatePermissionsLP(deletedPs.name, null, false, false, false);
                        if (deletedPs.sharedUser != null) {
                            // remove permissions associated with package
                            mSettings.updateSharedUserPermsLP(deletedPs, mGlobalGids);
                        }
                    }
                }
                // remove from preferred activities.
                ArrayList<PreferredActivity> removed = new ArrayList<PreferredActivity>();
                for (PreferredActivity pa : mSettings.mPreferredActivities.filterSet()) {
                    if (pa.mActivity.getPackageName().equals(deletedPs.name)) {
                        removed.add(pa);
                    }
                }
                for (PreferredActivity pa : removed) {
                    mSettings.mPreferredActivities.removeFilter(pa);
                }
            }
            // Save settings now
            mSettings.writeLP();
        }
    }

    /*
     * Tries to delete system package.
     */
    private boolean deleteSystemPackageLI(PackageParser.Package p,
            int flags, PackageRemovedInfo outInfo) {
        ApplicationInfo applicationInfo = p.applicationInfo;
        //applicable for non-partially installed applications only
        if (applicationInfo == null) {
            Slog.w(TAG, "Package " + p.packageName + " has no applicationInfo.");
            return false;
        }
        PackageSetting ps = null;
        // Confirm if the system package has been updated
        // An updated system app can be deleted. This will also have to restore
        // the system pkg from system partition
        synchronized (mPackages) {
            ps = mSettings.getDisabledSystemPkg(p.packageName);
        }
        if (ps == null) {
            Slog.w(TAG, "Attempt to delete system package "+ p.packageName);
            return false;
        } else {
            Log.i(TAG, "Deleting system pkg from data partition");
        }
        // Delete the updated package
        outInfo.isRemovedPackageSystemUpdate = true;
        boolean deleteCodeAndResources = false;
        if (ps.versionCode <  p.mVersionCode) {
            // Delete code and resources for downgrades
            deleteCodeAndResources = true;
            if ((flags & PackageManager.DONT_DELETE_DATA) == 0) {
                flags &= ~PackageManager.DONT_DELETE_DATA;
            }
        } else {
            // Preserve data by setting flag
            if ((flags & PackageManager.DONT_DELETE_DATA) == 0) {
                flags |= PackageManager.DONT_DELETE_DATA;
            }
        }
        boolean ret = deleteInstalledPackageLI(p, deleteCodeAndResources, flags, outInfo);
        if (!ret) {
            return false;
        }
        synchronized (mPackages) {
            // Reinstate the old system package
            mSettings.enableSystemPackageLP(p.packageName);
            // Remove any native libraries.
            removeNativeBinariesLI(p);
        }
        // Install the system package
        PackageParser.Package newPkg = scanPackageLI(ps.codePath,
                PackageParser.PARSE_MUST_BE_APK | PackageParser.PARSE_IS_SYSTEM,
                SCAN_MONITOR | SCAN_NO_PATHS);

        if (newPkg == null) {
            Slog.w(TAG, "Failed to restore system package:"+p.packageName+" with error:" + mLastScanError);
            return false;
        }
        synchronized (mPackages) {
            updatePermissionsLP(newPkg.packageName, newPkg, true, true, false);
            mSettings.writeLP();
        }
        return true;
    }

    private boolean deleteInstalledPackageLI(PackageParser.Package p,
            boolean deleteCodeAndResources, int flags, PackageRemovedInfo outInfo) {
        ApplicationInfo applicationInfo = p.applicationInfo;
        if (applicationInfo == null) {
            Slog.w(TAG, "Package " + p.packageName + " has no applicationInfo.");
            return false;
        }
        if (outInfo != null) {
            outInfo.uid = applicationInfo.uid;
        }

        // Delete package data from internal structures and also remove data if flag is set
        removePackageDataLI(p, outInfo, flags);

        // Delete application code and resources
        if (deleteCodeAndResources) {
            // TODO can pick up from PackageSettings as well
            int installFlags = ((p.applicationInfo.flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE)!=0) ?
                    PackageManager.INSTALL_EXTERNAL : 0;
            installFlags |= ((p.applicationInfo.flags & ApplicationInfo.FLAG_FORWARD_LOCK)!=0) ?
                    PackageManager.INSTALL_FORWARD_LOCK : 0;
            outInfo.args = createInstallArgs(installFlags,
                    applicationInfo.sourceDir, applicationInfo.publicSourceDir);
        }
        return true;
    }

    /*
     * This method handles package deletion in general
     */
    private boolean deletePackageLI(String packageName,
            boolean deleteCodeAndResources, int flags, PackageRemovedInfo outInfo) {
        if (packageName == null) {
            Slog.w(TAG, "Attempt to delete null packageName.");
            return false;
        }
        PackageParser.Package p;
        boolean dataOnly = false;
        synchronized (mPackages) {
            p = mPackages.get(packageName);
            if (p == null) {
                //this retrieves partially installed apps
                dataOnly = true;
                PackageSetting ps = mSettings.mPackages.get(packageName);
                if (ps == null) {
                    Slog.w(TAG, "Package named '" + packageName +"' doesn't exist.");
                    return false;
                }
                p = ps.pkg;
            }
        }
        if (p == null) {
            Slog.w(TAG, "Package named '" + packageName +"' doesn't exist.");
            return false;
        }

        if (dataOnly) {
            // Delete application data first
            removePackageDataLI(p, outInfo, flags);
            return true;
        }
        // At this point the package should have ApplicationInfo associated with it
        if (p.applicationInfo == null) {
            Slog.w(TAG, "Package " + p.packageName + " has no applicationInfo.");
            return false;
        }
        boolean ret = false;
        if ( (p.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
            Log.i(TAG, "Removing system package:"+p.packageName);
            // When an updated system application is deleted we delete the existing resources as well and
            // fall back to existing code in system partition
            ret = deleteSystemPackageLI(p, flags, outInfo);
        } else {
            Log.i(TAG, "Removing non-system package:"+p.packageName);
            // Kill application pre-emptively especially for apps on sd.
            killApplication(packageName, p.applicationInfo.uid);
            ret = deleteInstalledPackageLI(p, deleteCodeAndResources, flags, outInfo);
        }
        return ret;
    }

    public void clearApplicationUserData(final String packageName,
            final IPackageDataObserver observer) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CLEAR_APP_USER_DATA, null);
        // Queue up an async operation since the package deletion may take a little while.
        mHandler.post(new Runnable() {
            public void run() {
                mHandler.removeCallbacks(this);
                final boolean succeeded;
                synchronized (mInstallLock) {
                    succeeded = clearApplicationUserDataLI(packageName);
                }
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

    private boolean clearApplicationUserDataLI(String packageName) {
        if (packageName == null) {
            Slog.w(TAG, "Attempt to delete null packageName.");
            return false;
        }
        PackageParser.Package p;
        boolean dataOnly = false;
        synchronized (mPackages) {
            p = mPackages.get(packageName);
            if(p == null) {
                dataOnly = true;
                PackageSetting ps = mSettings.mPackages.get(packageName);
                if((ps == null) || (ps.pkg == null)) {
                    Slog.w(TAG, "Package named '" + packageName +"' doesn't exist.");
                    return false;
                }
                p = ps.pkg;
            }
        }

        if(!dataOnly) {
            //need to check this only for fully installed applications
            if (p == null) {
                Slog.w(TAG, "Package named '" + packageName +"' doesn't exist.");
                return false;
            }
            final ApplicationInfo applicationInfo = p.applicationInfo;
            if (applicationInfo == null) {
                Slog.w(TAG, "Package " + packageName + " has no applicationInfo.");
                return false;
            }
        }
        if (mInstaller != null) {
            int retCode = mInstaller.clearUserData(packageName);
            if (retCode < 0) {
                Slog.w(TAG, "Couldn't remove cache files for package: "
                        + packageName);
                return false;
            }
        }
        return true;
    }

    public void deleteApplicationCacheFiles(final String packageName,
            final IPackageDataObserver observer) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.DELETE_CACHE_FILES, null);
        // Queue up an async operation since the package deletion may take a little while.
        mHandler.post(new Runnable() {
            public void run() {
                mHandler.removeCallbacks(this);
                final boolean succeded;
                synchronized (mInstallLock) {
                    succeded = deleteApplicationCacheFilesLI(packageName);
                }
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

    private boolean deleteApplicationCacheFilesLI(String packageName) {
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
        if (mInstaller != null) {
            int retCode = mInstaller.deleteCacheFiles(packageName);
            if (retCode < 0) {
                Slog.w(TAG, "Couldn't remove cache files for package: "
                           + packageName);
                return false;
            }
        }
        return true;
    }

    public void getPackageSizeInfo(final String packageName,
            final IPackageStatsObserver observer) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.GET_PACKAGE_SIZE, null);
        // Queue up an async operation since the package deletion may take a little while.
        mHandler.post(new Runnable() {
            public void run() {
                mHandler.removeCallbacks(this);
                PackageStats lStats = new PackageStats(packageName);
                final boolean succeded;
                synchronized (mInstallLock) {
                    succeded = getPackageSizeInfoLI(packageName, lStats);
                }
                if(observer != null) {
                    try {
                        observer.onGetStatsCompleted(lStats, succeded);
                    } catch (RemoteException e) {
                        Log.i(TAG, "Observer no longer exists.");
                    }
                } //end if observer
            } //end run
        });
    }

    private boolean getPackageSizeInfoLI(String packageName, PackageStats pStats) {
        if (packageName == null) {
            Slog.w(TAG, "Attempt to get size of null packageName.");
            return false;
        }
        PackageParser.Package p;
        boolean dataOnly = false;
        synchronized (mPackages) {
            p = mPackages.get(packageName);
            if(p == null) {
                dataOnly = true;
                PackageSetting ps = mSettings.mPackages.get(packageName);
                if((ps == null) || (ps.pkg == null)) {
                    Slog.w(TAG, "Package named '" + packageName +"' doesn't exist.");
                    return false;
                }
                p = ps.pkg;
            }
        }
        String publicSrcDir = null;
        if(!dataOnly) {
            final ApplicationInfo applicationInfo = p.applicationInfo;
            if (applicationInfo == null) {
                Slog.w(TAG, "Package " + packageName + " has no applicationInfo.");
                return false;
            }
            publicSrcDir = isForwardLocked(p) ? applicationInfo.publicSourceDir : null;
        }
        if (mInstaller != null) {
            int res = mInstaller.getSizeInfo(packageName, p.mPath,
                    publicSrcDir, pStats);
            if (res < 0) {
                return false;
            } else {
                return true;
            }
        }
        return true;
    }


    public void addPackageToPreferred(String packageName) {
        Slog.w(TAG, "addPackageToPreferred: this is now a no-op");
    }

    public void removePackageFromPreferred(String packageName) {
        Slog.w(TAG, "removePackageFromPreferred: this is now a no-op");
    }

    public List<PackageInfo> getPreferredPackages(int flags) {
        return new ArrayList<PackageInfo>();
    }

    int getUidTargetSdkVersionLockedLP(int uid) {
        Object obj = mSettings.getUserIdLP(uid);
        if (obj instanceof SharedUserSetting) {
            SharedUserSetting sus = (SharedUserSetting)obj;
            final int N = sus.packages.size();
            int vers = Build.VERSION_CODES.CUR_DEVELOPMENT;
            Iterator<PackageSetting> it = sus.packages.iterator();
            int i=0;
            while (it.hasNext()) {
                PackageSetting ps = it.next();
                if (ps.pkg != null) {
                    int v = ps.pkg.applicationInfo.targetSdkVersion;
                    if (v < vers) vers = v;
                }
            }
            return vers;
        } else if (obj instanceof PackageSetting) {
            PackageSetting ps = (PackageSetting)obj;
            if (ps.pkg != null) {
                return ps.pkg.applicationInfo.targetSdkVersion;
            }
        }
        return Build.VERSION_CODES.CUR_DEVELOPMENT;
    }
    
    public void addPreferredActivity(IntentFilter filter, int match,
            ComponentName[] set, ComponentName activity) {
        synchronized (mPackages) {
            if (mContext.checkCallingOrSelfPermission(
                    android.Manifest.permission.SET_PREFERRED_APPLICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                if (getUidTargetSdkVersionLockedLP(Binder.getCallingUid())
                        < Build.VERSION_CODES.FROYO) {
                    Slog.w(TAG, "Ignoring addPreferredActivity() from uid "
                            + Binder.getCallingUid());
                    return;
                }
                mContext.enforceCallingOrSelfPermission(
                        android.Manifest.permission.SET_PREFERRED_APPLICATIONS, null);
            }
            
            Slog.i(TAG, "Adding preferred activity " + activity + ":");
            filter.dump(new LogPrinter(Log.INFO, TAG), "  ");
            mSettings.mPreferredActivities.addFilter(
                    new PreferredActivity(filter, match, set, activity));
            scheduleWriteSettingsLocked();            
        }
    }

    public void replacePreferredActivity(IntentFilter filter, int match,
            ComponentName[] set, ComponentName activity) {
        if (filter.countActions() != 1) {
            throw new IllegalArgumentException(
                    "replacePreferredActivity expects filter to have only 1 action.");
        }
        if (filter.countCategories() != 1) {
            throw new IllegalArgumentException(
                    "replacePreferredActivity expects filter to have only 1 category.");
        }
        if (filter.countDataAuthorities() != 0
                || filter.countDataPaths() != 0
                || filter.countDataSchemes() != 0
                || filter.countDataTypes() != 0) {
            throw new IllegalArgumentException(
                    "replacePreferredActivity expects filter to have no data authorities, " +
                    "paths, schemes or types.");
        }
        synchronized (mPackages) {
            if (mContext.checkCallingOrSelfPermission(
                    android.Manifest.permission.SET_PREFERRED_APPLICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                if (getUidTargetSdkVersionLockedLP(Binder.getCallingUid())
                        < Build.VERSION_CODES.FROYO) {
                    Slog.w(TAG, "Ignoring replacePreferredActivity() from uid "
                            + Binder.getCallingUid());
                    return;
                }
                mContext.enforceCallingOrSelfPermission(
                        android.Manifest.permission.SET_PREFERRED_APPLICATIONS, null);
            }
            
            Iterator<PreferredActivity> it = mSettings.mPreferredActivities.filterIterator();
            String action = filter.getAction(0);
            String category = filter.getCategory(0);
            while (it.hasNext()) {
                PreferredActivity pa = it.next();
                if (pa.getAction(0).equals(action) && pa.getCategory(0).equals(category)) {
                    it.remove();
                    Log.i(TAG, "Removed preferred activity " + pa.mActivity + ":");
                    filter.dump(new LogPrinter(Log.INFO, TAG), "  ");
                }
            }
            addPreferredActivity(filter, match, set, activity);
        }
    }

    public void clearPackagePreferredActivities(String packageName) {
        synchronized (mPackages) {
            int uid = Binder.getCallingUid();
            PackageParser.Package pkg = mPackages.get(packageName);
            if (pkg == null || pkg.applicationInfo.uid != uid) {
                if (mContext.checkCallingOrSelfPermission(
                        android.Manifest.permission.SET_PREFERRED_APPLICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                    if (getUidTargetSdkVersionLockedLP(Binder.getCallingUid())
                            < Build.VERSION_CODES.FROYO) {
                        Slog.w(TAG, "Ignoring clearPackagePreferredActivities() from uid "
                                + Binder.getCallingUid());
                        return;
                    }
                    mContext.enforceCallingOrSelfPermission(
                            android.Manifest.permission.SET_PREFERRED_APPLICATIONS, null);
                }
            }

            if (clearPackagePreferredActivitiesLP(packageName)) {
                scheduleWriteSettingsLocked();            
            }
        }
    }

    boolean clearPackagePreferredActivitiesLP(String packageName) {
        boolean changed = false;
        Iterator<PreferredActivity> it = mSettings.mPreferredActivities.filterIterator();
        while (it.hasNext()) {
            PreferredActivity pa = it.next();
            if (pa.mActivity.getPackageName().equals(packageName)) {
                it.remove();
                changed = true;
            }
        }
        return changed;
    }

    public int getPreferredActivities(List<IntentFilter> outFilters,
            List<ComponentName> outActivities, String packageName) {

        int num = 0;
        synchronized (mPackages) {
            Iterator<PreferredActivity> it = mSettings.mPreferredActivities.filterIterator();
            while (it.hasNext()) {
                PreferredActivity pa = it.next();
                if (packageName == null
                        || pa.mActivity.getPackageName().equals(packageName)) {
                    if (outFilters != null) {
                        outFilters.add(new IntentFilter(pa));
                    }
                    if (outActivities != null) {
                        outActivities.add(pa.mActivity);
                    }
                }
            }
        }

        return num;
    }

    public void setApplicationEnabledSetting(String appPackageName,
            int newState, int flags) {
        setEnabledSetting(appPackageName, null, newState, flags);
    }

    public void setComponentEnabledSetting(ComponentName componentName,
            int newState, int flags) {
        setEnabledSetting(componentName.getPackageName(),
                componentName.getClassName(), newState, flags);
    }

    private void setEnabledSetting(
            final String packageName, String className, int newState, final int flags) {
        if (!(newState == COMPONENT_ENABLED_STATE_DEFAULT
              || newState == COMPONENT_ENABLED_STATE_ENABLED
              || newState == COMPONENT_ENABLED_STATE_DISABLED)) {
            throw new IllegalArgumentException("Invalid new component state: "
                    + newState);
        }
        PackageSetting pkgSetting;
        final int uid = Binder.getCallingUid();
        final int permission = mContext.checkCallingPermission(
                android.Manifest.permission.CHANGE_COMPONENT_ENABLED_STATE);
        final boolean allowedByPermission = (permission == PackageManager.PERMISSION_GRANTED);
        boolean sendNow = false;
        boolean isApp = (className == null);
        String componentName = isApp ? packageName : className;
        int packageUid = -1;
        ArrayList<String> components;
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
            if (!allowedByPermission && (uid != pkgSetting.userId)) {
                throw new SecurityException(
                        "Permission Denial: attempt to change component state from pid="
                        + Binder.getCallingPid()
                        + ", uid=" + uid + ", package uid=" + pkgSetting.userId);
            }
            if (className == null) {
                // We're dealing with an application/package level state change
                if (pkgSetting.enabled == newState) {
                    // Nothing to do
                    return;
                }
                pkgSetting.enabled = newState;
            } else {
                // We're dealing with a component level state change
                switch (newState) {
                case COMPONENT_ENABLED_STATE_ENABLED:
                    if (!pkgSetting.enableComponentLP(className)) {
                        return;
                    }
                    break;
                case COMPONENT_ENABLED_STATE_DISABLED:
                    if (!pkgSetting.disableComponentLP(className)) {
                        return;
                    }
                    break;
                case COMPONENT_ENABLED_STATE_DEFAULT:
                    if (!pkgSetting.restoreComponentLP(className)) {
                        return;
                    }
                    break;
                default:
                    Slog.e(TAG, "Invalid new component state: " + newState);
                    return;
                }
            }
            mSettings.writeLP();
            packageUid = pkgSetting.userId;
            components = mPendingBroadcasts.get(packageName);
            boolean newPackage = components == null;
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
                mPendingBroadcasts.remove(packageName);
            } else {
                if (newPackage) {
                    mPendingBroadcasts.put(packageName, components);
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
                sendPackageChangedBroadcast(packageName,
                        (flags&PackageManager.DONT_KILL_APP) != 0, components, packageUid);
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    private void sendPackageChangedBroadcast(String packageName,
            boolean killFlag, ArrayList<String> componentNames, int packageUid) {
        if (false) Log.v(TAG, "Sending package changed: package=" + packageName
                + " components=" + componentNames);
        Bundle extras = new Bundle(4);
        extras.putString(Intent.EXTRA_CHANGED_COMPONENT_NAME, componentNames.get(0));
        String nameList[] = new String[componentNames.size()];
        componentNames.toArray(nameList);
        extras.putStringArray(Intent.EXTRA_CHANGED_COMPONENT_NAME_LIST, nameList);
        extras.putBoolean(Intent.EXTRA_DONT_KILL_APP, killFlag);
        extras.putInt(Intent.EXTRA_UID, packageUid);
        sendPackageBroadcast(Intent.ACTION_PACKAGE_CHANGED,  packageName, extras, null);
    }

    public String getInstallerPackageName(String packageName) {
        synchronized (mPackages) {
            PackageSetting pkg = mSettings.mPackages.get(packageName);
            if (pkg == null) {
                throw new IllegalArgumentException("Unknown package: " + packageName);
            }
            return pkg.installerPackageName;
        }
    }

    public int getApplicationEnabledSetting(String appPackageName) {
        synchronized (mPackages) {
            PackageSetting pkg = mSettings.mPackages.get(appPackageName);
            if (pkg == null) {
                throw new IllegalArgumentException("Unknown package: " + appPackageName);
            }
            return pkg.enabled;
        }
    }

    public int getComponentEnabledSetting(ComponentName componentName) {
        synchronized (mPackages) {
            final String packageNameStr = componentName.getPackageName();
            PackageSetting pkg = mSettings.mPackages.get(packageNameStr);
            if (pkg == null) {
                throw new IllegalArgumentException("Unknown component: " + componentName);
            }
            final String classNameStr = componentName.getClassName();
            return pkg.currentEnabledStateLP(classNameStr);
        }
    }

    public void enterSafeMode() {
        if (!mSystemReady) {
            mSafeMode = true;
        }
    }

    public void systemReady() {
        mSystemReady = true;

        // Read the compatibilty setting when the system is ready.
        boolean compatibilityModeEnabled = android.provider.Settings.System.getInt(
                mContext.getContentResolver(),
                android.provider.Settings.System.COMPATIBILITY_MODE, 1) == 1;
        PackageParser.setCompatibilityModeEnabled(compatibilityModeEnabled);
        if (DEBUG_SETTINGS) {
            Log.d(TAG, "compatibility mode:" + compatibilityModeEnabled);
        }
    }

    public boolean isSafeMode() {
        return mSafeMode;
    }

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
                pw.println("  [-h] [cmd] ...");
                pw.println("  cmd may be one of:");
                pw.println("    [package.name]: info about given package");
                return;
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
            }
        }
        
        boolean printedTitle = false;
        
        synchronized (mPackages) {
            if (mActivities.dump(pw, "Activity Resolver Table:", "  ", packageName)) {
                printedTitle = true;
            }
            if (mReceivers.dump(pw, printedTitle
                    ? "\nReceiver Resolver Table:" : "Receiver Resolver Table:",
                    "  ", packageName)) {
                printedTitle = true;
            }
            if (mServices.dump(pw, printedTitle
                    ? "\nService Resolver Table:" : "Service Resolver Table:",
                    "  ", packageName)) {
                printedTitle = true;
            }
            if (mSettings.mPreferredActivities.dump(pw, printedTitle
                    ? "\nPreferred Activities:" : "Preferred Activities:",
                    "  ", packageName)) {
                printedTitle = true;
            }
            boolean printedSomething = false;
            {
                for (BasePermission p : mSettings.mPermissions.values()) {
                    if (packageName != null && !packageName.equals(p.sourcePackage)) {
                        continue;
                    }
                    if (!printedSomething) {
                        if (printedTitle) pw.println(" ");
                        pw.println("Permissions:");
                        printedSomething = true;
                        printedTitle = true;
                    }
                    pw.print("  Permission ["); pw.print(p.name); pw.print("] (");
                            pw.print(Integer.toHexString(System.identityHashCode(p)));
                            pw.println("):");
                    pw.print("    sourcePackage="); pw.println(p.sourcePackage);
                    pw.print("    uid="); pw.print(p.uid);
                            pw.print(" gids="); pw.print(arrayToString(p.gids));
                            pw.print(" type="); pw.print(p.type);
                            pw.print(" prot="); pw.println(p.protectionLevel);
                    if (p.packageSetting != null) {
                        pw.print("    packageSetting="); pw.println(p.packageSetting);
                    }
                    if (p.perm != null) {
                        pw.print("    perm="); pw.println(p.perm);
                    }
                }
            }
            printedSomething = false;
            SharedUserSetting packageSharedUser = null;
            {
                for (PackageSetting ps : mSettings.mPackages.values()) {
                    if (packageName != null && !packageName.equals(ps.realName)
                            && !packageName.equals(ps.name)) {
                        continue;
                    }
                    if (!printedSomething) {
                        if (printedTitle) pw.println(" ");
                        pw.println("Packages:");
                        printedSomething = true;
                        printedTitle = true;
                    }
                    packageSharedUser = ps.sharedUser;
                    pw.print("  Package [");
                            pw.print(ps.realName != null ? ps.realName : ps.name);
                            pw.print("] (");
                            pw.print(Integer.toHexString(System.identityHashCode(ps)));
                            pw.println("):");
                    if (ps.realName != null) {
                        pw.print("    compat name="); pw.println(ps.name);
                    }
                    pw.print("    userId="); pw.print(ps.userId);
                            pw.print(" gids="); pw.println(arrayToString(ps.gids));
                    pw.print("    sharedUser="); pw.println(ps.sharedUser);
                    pw.print("    pkg="); pw.println(ps.pkg);
                    pw.print("    codePath="); pw.println(ps.codePathString);
                    pw.print("    resourcePath="); pw.println(ps.resourcePathString);
                    if (ps.pkg != null) {
                        pw.print("    dataDir="); pw.println(ps.pkg.applicationInfo.dataDir);
                        pw.print("    targetSdk="); pw.println(ps.pkg.applicationInfo.targetSdkVersion);
                        pw.print("    supportsScreens=[");
                        boolean first = true;
                        if ((ps.pkg.applicationInfo.flags &
                                ApplicationInfo.FLAG_SUPPORTS_NORMAL_SCREENS) != 0) {
                            if (!first) pw.print(", ");
                            first = false;
                            pw.print("medium");
                        }
                        if ((ps.pkg.applicationInfo.flags &
                                ApplicationInfo.FLAG_SUPPORTS_LARGE_SCREENS) != 0) {
                            if (!first) pw.print(", ");
                            first = false;
                            pw.print("large");
                        }
                        if ((ps.pkg.applicationInfo.flags &
                                ApplicationInfo.FLAG_SUPPORTS_SMALL_SCREENS) != 0) {
                            if (!first) pw.print(", ");
                            first = false;
                            pw.print("small");
                        }
                        if ((ps.pkg.applicationInfo.flags &
                                ApplicationInfo.FLAG_RESIZEABLE_FOR_SCREENS) != 0) {
                            if (!first) pw.print(", ");
                            first = false;
                            pw.print("resizeable");
                        }
                        if ((ps.pkg.applicationInfo.flags &
                                ApplicationInfo.FLAG_SUPPORTS_SCREEN_DENSITIES) != 0) {
                            if (!first) pw.print(", ");
                            first = false;
                            pw.print("anyDensity");
                        }
                    }
                    pw.println("]");
                    pw.print("    timeStamp="); pw.println(ps.getTimeStampStr());
                    pw.print("    signatures="); pw.println(ps.signatures);
                    pw.print("    permissionsFixed="); pw.print(ps.permissionsFixed);
                            pw.print(" haveGids="); pw.println(ps.haveGids);
                    pw.print("    pkgFlags=0x"); pw.print(Integer.toHexString(ps.pkgFlags));
                            pw.print(" installStatus="); pw.print(ps.installStatus);
                            pw.print(" enabled="); pw.println(ps.enabled);
                    if (ps.disabledComponents.size() > 0) {
                        pw.println("    disabledComponents:");
                        for (String s : ps.disabledComponents) {
                            pw.print("      "); pw.println(s);
                        }
                    }
                    if (ps.enabledComponents.size() > 0) {
                        pw.println("    enabledComponents:");
                        for (String s : ps.enabledComponents) {
                            pw.print("      "); pw.println(s);
                        }
                    }
                    if (ps.grantedPermissions.size() > 0) {
                        pw.println("    grantedPermissions:");
                        for (String s : ps.grantedPermissions) {
                            pw.print("      "); pw.println(s);
                        }
                    }
                }
            }
            printedSomething = false;
            if (mSettings.mRenamedPackages.size() > 0) {
                for (HashMap.Entry<String, String> e
                        : mSettings.mRenamedPackages.entrySet()) {
                    if (packageName != null && !packageName.equals(e.getKey())
                            && !packageName.equals(e.getValue())) {
                        continue;
                    }
                    if (!printedSomething) {
                        if (printedTitle) pw.println(" ");
                        pw.println("Renamed packages:");
                        printedSomething = true;
                        printedTitle = true;
                    }
                    pw.print("  "); pw.print(e.getKey()); pw.print(" -> ");
                            pw.println(e.getValue());
                }
            }
            printedSomething = false;
            if (mSettings.mDisabledSysPackages.size() > 0) {
                for (PackageSetting ps : mSettings.mDisabledSysPackages.values()) {
                    if (packageName != null && !packageName.equals(ps.realName)
                            && !packageName.equals(ps.name)) {
                        continue;
                    }
                    if (!printedSomething) {
                        if (printedTitle) pw.println(" ");
                        pw.println("Hidden system packages:");
                        printedSomething = true;
                        printedTitle = true;
                    }
                   pw.print("  Package [");
                            pw.print(ps.realName != null ? ps.realName : ps.name);
                            pw.print("] (");
                            pw.print(Integer.toHexString(System.identityHashCode(ps)));
                            pw.println("):");
                    if (ps.realName != null) {
                        pw.print("    compat name="); pw.println(ps.name);
                    }
                    pw.print("    userId="); pw.println(ps.userId);
                    pw.print("    sharedUser="); pw.println(ps.sharedUser);
                    pw.print("    codePath="); pw.println(ps.codePathString);
                    pw.print("    resourcePath="); pw.println(ps.resourcePathString);
                }
            }
            printedSomething = false;
            {
                for (SharedUserSetting su : mSettings.mSharedUsers.values()) {
                    if (packageName != null && su != packageSharedUser) {
                        continue;
                    }
                    if (!printedSomething) {
                        if (printedTitle) pw.println(" ");
                        pw.println("Shared users:");
                        printedSomething = true;
                        printedTitle = true;
                    }
                    pw.print("  SharedUser ["); pw.print(su.name); pw.print("] (");
                            pw.print(Integer.toHexString(System.identityHashCode(su)));
                            pw.println("):");
                    pw.print("    userId="); pw.print(su.userId);
                            pw.print(" gids="); pw.println(arrayToString(su.gids));
                    pw.println("    grantedPermissions:");
                    for (String s : su.grantedPermissions) {
                        pw.print("      "); pw.println(s);
                    }
                }
            }
            
            if (packageName == null) {
                if (printedTitle) pw.println(" ");
                printedTitle = true;
                pw.println("Settings parse messages:");
                pw.println(mSettings.mReadMessages.toString());
                
                pw.println(" ");
                pw.println("Package warning messages:");
                File fname = getSettingsProblemFile();
                FileInputStream in;
                try {
                    in = new FileInputStream(fname);
                    int avail = in.available();
                    byte[] data = new byte[avail];
                    in.read(data);
                    pw.println(new String(data));
                } catch (FileNotFoundException e) {
                } catch (IOException e) {
                }
            }
        }

        synchronized (mProviders) {
            boolean printedSomething = false;
            for (PackageParser.Provider p : mProviders.values()) {
                if (packageName != null && !packageName.equals(p.info.packageName)) {
                    continue;
                }
                if (!printedSomething) {
                    if (printedTitle) pw.println(" ");
                    pw.println("Registered ContentProviders:");
                    printedSomething = true;
                    printedTitle = true;
                }
                pw.print("  ["); pw.print(p.info.authority); pw.print("]: ");
                        pw.println(p.toString());
            }
        }
    }

    static final class BasePermission {
        final static int TYPE_NORMAL = 0;
        final static int TYPE_BUILTIN = 1;
        final static int TYPE_DYNAMIC = 2;

        final String name;
        String sourcePackage;
        PackageSettingBase packageSetting;
        final int type;
        int protectionLevel;
        PackageParser.Permission perm;
        PermissionInfo pendingInfo;
        int uid;
        int[] gids;

        BasePermission(String _name, String _sourcePackage, int _type) {
            name = _name;
            sourcePackage = _sourcePackage;
            type = _type;
            // Default to most conservative protection level.
            protectionLevel = PermissionInfo.PROTECTION_SIGNATURE;
        }
        
        public String toString() {
            return "BasePermission{"
                + Integer.toHexString(System.identityHashCode(this))
                + " " + name + "}";
        }
    }

    static class PackageSignatures {
        private Signature[] mSignatures;

        PackageSignatures(Signature[] sigs) {
            assignSignatures(sigs);
        }

        PackageSignatures() {
        }

        void writeXml(XmlSerializer serializer, String tagName,
                ArrayList<Signature> pastSignatures) throws IOException {
            if (mSignatures == null) {
                return;
            }
            serializer.startTag(null, tagName);
            serializer.attribute(null, "count",
                    Integer.toString(mSignatures.length));
            for (int i=0; i<mSignatures.length; i++) {
                serializer.startTag(null, "cert");
                final Signature sig = mSignatures[i];
                final int sigHash = sig.hashCode();
                final int numPast = pastSignatures.size();
                int j;
                for (j=0; j<numPast; j++) {
                    Signature pastSig = pastSignatures.get(j);
                    if (pastSig.hashCode() == sigHash && pastSig.equals(sig)) {
                        serializer.attribute(null, "index", Integer.toString(j));
                        break;
                    }
                }
                if (j >= numPast) {
                    pastSignatures.add(sig);
                    serializer.attribute(null, "index", Integer.toString(numPast));
                    serializer.attribute(null, "key", sig.toCharsString());
                }
                serializer.endTag(null, "cert");
            }
            serializer.endTag(null, tagName);
        }

        void readXml(XmlPullParser parser, ArrayList<Signature> pastSignatures)
                throws IOException, XmlPullParserException {
            String countStr = parser.getAttributeValue(null, "count");
            if (countStr == null) {
                reportSettingsProblem(Log.WARN,
                        "Error in package manager settings: <signatures> has"
                           + " no count at " + parser.getPositionDescription());
                XmlUtils.skipCurrentTag(parser);
            }
            final int count = Integer.parseInt(countStr);
            mSignatures = new Signature[count];
            int pos = 0;

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
                if (tagName.equals("cert")) {
                    if (pos < count) {
                        String index = parser.getAttributeValue(null, "index");
                        if (index != null) {
                            try {
                                int idx = Integer.parseInt(index);
                                String key = parser.getAttributeValue(null, "key");
                                if (key == null) {
                                    if (idx >= 0 && idx < pastSignatures.size()) {
                                        Signature sig = pastSignatures.get(idx);
                                        if (sig != null) {
                                            mSignatures[pos] = pastSignatures.get(idx);
                                            pos++;
                                        } else {
                                            reportSettingsProblem(Log.WARN,
                                                    "Error in package manager settings: <cert> "
                                                       + "index " + index + " is not defined at "
                                                       + parser.getPositionDescription());
                                        }
                                    } else {
                                        reportSettingsProblem(Log.WARN,
                                                "Error in package manager settings: <cert> "
                                                   + "index " + index + " is out of bounds at "
                                                   + parser.getPositionDescription());
                                    }
                                } else {
                                    while (pastSignatures.size() <= idx) {
                                        pastSignatures.add(null);
                                    }
                                    Signature sig = new Signature(key);
                                    pastSignatures.set(idx, sig);
                                    mSignatures[pos] = sig;
                                    pos++;
                                }
                            } catch (NumberFormatException e) {
                                reportSettingsProblem(Log.WARN,
                                        "Error in package manager settings: <cert> "
                                           + "index " + index + " is not a number at "
                                           + parser.getPositionDescription());
                            }
                        } else {
                            reportSettingsProblem(Log.WARN,
                                    "Error in package manager settings: <cert> has"
                                       + " no index at " + parser.getPositionDescription());
                        }
                    } else {
                        reportSettingsProblem(Log.WARN,
                                "Error in package manager settings: too "
                                   + "many <cert> tags, expected " + count
                                   + " at " + parser.getPositionDescription());
                    }
                } else {
                    reportSettingsProblem(Log.WARN,
                            "Unknown element under <cert>: "
                            + parser.getName());
                }
                XmlUtils.skipCurrentTag(parser);
            }

            if (pos < count) {
                // Should never happen -- there is an error in the written
                // settings -- but if it does we don't want to generate
                // a bad array.
                Signature[] newSigs = new Signature[pos];
                System.arraycopy(mSignatures, 0, newSigs, 0, pos);
                mSignatures = newSigs;
            }
        }

        private void assignSignatures(Signature[] sigs) {
            if (sigs == null) {
                mSignatures = null;
                return;
            }
            mSignatures = new Signature[sigs.length];
            for (int i=0; i<sigs.length; i++) {
                mSignatures[i] = sigs[i];
            }
        }

        @Override
        public String toString() {
            StringBuffer buf = new StringBuffer(128);
            buf.append("PackageSignatures{");
            buf.append(Integer.toHexString(System.identityHashCode(this)));
            buf.append(" [");
            if (mSignatures != null) {
                for (int i=0; i<mSignatures.length; i++) {
                    if (i > 0) buf.append(", ");
                    buf.append(Integer.toHexString(
                            System.identityHashCode(mSignatures[i])));
                }
            }
            buf.append("]}");
            return buf.toString();
        }
    }

    static class PreferredActivity extends IntentFilter {
        final int mMatch;
        final String[] mSetPackages;
        final String[] mSetClasses;
        final String[] mSetComponents;
        final ComponentName mActivity;
        final String mShortActivity;
        String mParseError;

        PreferredActivity(IntentFilter filter, int match, ComponentName[] set,
                ComponentName activity) {
            super(filter);
            mMatch = match&IntentFilter.MATCH_CATEGORY_MASK;
            mActivity = activity;
            mShortActivity = activity.flattenToShortString();
            mParseError = null;
            if (set != null) {
                final int N = set.length;
                String[] myPackages = new String[N];
                String[] myClasses = new String[N];
                String[] myComponents = new String[N];
                for (int i=0; i<N; i++) {
                    ComponentName cn = set[i];
                    if (cn == null) {
                        mSetPackages = null;
                        mSetClasses = null;
                        mSetComponents = null;
                        return;
                    }
                    myPackages[i] = cn.getPackageName().intern();
                    myClasses[i] = cn.getClassName().intern();
                    myComponents[i] = cn.flattenToShortString().intern();
                }
                mSetPackages = myPackages;
                mSetClasses = myClasses;
                mSetComponents = myComponents;
            } else {
                mSetPackages = null;
                mSetClasses = null;
                mSetComponents = null;
            }
        }

        PreferredActivity(XmlPullParser parser) throws XmlPullParserException,
                IOException {
            mShortActivity = parser.getAttributeValue(null, "name");
            mActivity = ComponentName.unflattenFromString(mShortActivity);
            if (mActivity == null) {
                mParseError = "Bad activity name " + mShortActivity;
            }
            String matchStr = parser.getAttributeValue(null, "match");
            mMatch = matchStr != null ? Integer.parseInt(matchStr, 16) : 0;
            String setCountStr = parser.getAttributeValue(null, "set");
            int setCount = setCountStr != null ? Integer.parseInt(setCountStr) : 0;

            String[] myPackages = setCount > 0 ? new String[setCount] : null;
            String[] myClasses = setCount > 0 ? new String[setCount] : null;
            String[] myComponents = setCount > 0 ? new String[setCount] : null;

            int setPos = 0;

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
                //Log.i(TAG, "Parse outerDepth=" + outerDepth + " depth="
                //        + parser.getDepth() + " tag=" + tagName);
                if (tagName.equals("set")) {
                    String name = parser.getAttributeValue(null, "name");
                    if (name == null) {
                        if (mParseError == null) {
                            mParseError = "No name in set tag in preferred activity "
                                + mShortActivity;
                        }
                    } else if (setPos >= setCount) {
                        if (mParseError == null) {
                            mParseError = "Too many set tags in preferred activity "
                                + mShortActivity;
                        }
                    } else {
                        ComponentName cn = ComponentName.unflattenFromString(name);
                        if (cn == null) {
                            if (mParseError == null) {
                                mParseError = "Bad set name " + name + " in preferred activity "
                                    + mShortActivity;
                            }
                        } else {
                            myPackages[setPos] = cn.getPackageName();
                            myClasses[setPos] = cn.getClassName();
                            myComponents[setPos] = name;
                            setPos++;
                        }
                    }
                    XmlUtils.skipCurrentTag(parser);
                } else if (tagName.equals("filter")) {
                    //Log.i(TAG, "Starting to parse filter...");
                    readFromXml(parser);
                    //Log.i(TAG, "Finished filter: outerDepth=" + outerDepth + " depth="
                    //        + parser.getDepth() + " tag=" + parser.getName());
                } else {
                    reportSettingsProblem(Log.WARN,
                            "Unknown element under <preferred-activities>: "
                            + parser.getName());
                    XmlUtils.skipCurrentTag(parser);
                }
            }

            if (setPos != setCount) {
                if (mParseError == null) {
                    mParseError = "Not enough set tags (expected " + setCount
                        + " but found " + setPos + ") in " + mShortActivity;
                }
            }

            mSetPackages = myPackages;
            mSetClasses = myClasses;
            mSetComponents = myComponents;
        }

        public void writeToXml(XmlSerializer serializer) throws IOException {
            final int NS = mSetClasses != null ? mSetClasses.length : 0;
            serializer.attribute(null, "name", mShortActivity);
            serializer.attribute(null, "match", Integer.toHexString(mMatch));
            serializer.attribute(null, "set", Integer.toString(NS));
            for (int s=0; s<NS; s++) {
                serializer.startTag(null, "set");
                serializer.attribute(null, "name", mSetComponents[s]);
                serializer.endTag(null, "set");
            }
            serializer.startTag(null, "filter");
            super.writeToXml(serializer);
            serializer.endTag(null, "filter");
        }

        boolean sameSet(List<ResolveInfo> query, int priority) {
            if (mSetPackages == null) return false;
            final int NQ = query.size();
            final int NS = mSetPackages.length;
            int numMatch = 0;
            for (int i=0; i<NQ; i++) {
                ResolveInfo ri = query.get(i);
                if (ri.priority != priority) continue;
                ActivityInfo ai = ri.activityInfo;
                boolean good = false;
                for (int j=0; j<NS; j++) {
                    if (mSetPackages[j].equals(ai.packageName)
                            && mSetClasses[j].equals(ai.name)) {
                        numMatch++;
                        good = true;
                        break;
                    }
                }
                if (!good) return false;
            }
            return numMatch == NS;
        }
    }

    static class GrantedPermissions {
        int pkgFlags;

        HashSet<String> grantedPermissions = new HashSet<String>();
        int[] gids;

        GrantedPermissions(int pkgFlags) {
            setFlags(pkgFlags);
        }

        void setFlags(int pkgFlags) {
            this.pkgFlags = pkgFlags & (
                    ApplicationInfo.FLAG_SYSTEM |
                    ApplicationInfo.FLAG_FORWARD_LOCK |
                    ApplicationInfo.FLAG_EXTERNAL_STORAGE);
        }
    }

    /**
     * Settings base class for pending and resolved classes.
     */
    static class PackageSettingBase extends GrantedPermissions {
        final String name;
        final String realName;
        File codePath;
        String codePathString;
        File resourcePath;
        String resourcePathString;
        private long timeStamp;
        private String timeStampString = "0";
        int versionCode;

        boolean uidError;
        
        PackageSignatures signatures = new PackageSignatures();

        boolean permissionsFixed;
        boolean haveGids;

        /* Explicitly disabled components */
        HashSet<String> disabledComponents = new HashSet<String>(0);
        /* Explicitly enabled components */
        HashSet<String> enabledComponents = new HashSet<String>(0);
        int enabled = COMPONENT_ENABLED_STATE_DEFAULT;
        int installStatus = PKG_INSTALL_COMPLETE;

        PackageSettingBase origPackage;
        
        /* package name of the app that installed this package */
        String installerPackageName;

        PackageSettingBase(String name, String realName, File codePath, File resourcePath,
                int pVersionCode, int pkgFlags) {
            super(pkgFlags);
            this.name = name;
            this.realName = realName;
            init(codePath, resourcePath, pVersionCode);
        }

        void init(File codePath, File resourcePath, int pVersionCode) {
            this.codePath = codePath;
            this.codePathString = codePath.toString();
            this.resourcePath = resourcePath;
            this.resourcePathString = resourcePath.toString();
            this.versionCode = pVersionCode;
        }
        
        public void setInstallerPackageName(String packageName) {
            installerPackageName = packageName;
        }

        String getInstallerPackageName() {
            return installerPackageName;
        }

        public void setInstallStatus(int newStatus) {
            installStatus = newStatus;
        }

        public int getInstallStatus() {
            return installStatus;
        }

        public void setTimeStamp(long newStamp) {
            if (newStamp != timeStamp) {
                timeStamp = newStamp;
                timeStampString = Long.toString(newStamp);
            }
        }

        public void setTimeStamp(long newStamp, String newStampStr) {
            timeStamp = newStamp;
            timeStampString = newStampStr;
        }

        public long getTimeStamp() {
            return timeStamp;
        }

        public String getTimeStampStr() {
            return timeStampString;
        }

        public void copyFrom(PackageSettingBase base) {
            grantedPermissions = base.grantedPermissions;
            gids = base.gids;

            timeStamp = base.timeStamp;
            timeStampString = base.timeStampString;
            signatures = base.signatures;
            permissionsFixed = base.permissionsFixed;
            haveGids = base.haveGids;
            disabledComponents = base.disabledComponents;
            enabledComponents = base.enabledComponents;
            enabled = base.enabled;
            installStatus = base.installStatus;
        }

        boolean enableComponentLP(String componentClassName) {
            boolean changed = disabledComponents.remove(componentClassName);
            changed |= enabledComponents.add(componentClassName);
            return changed;
        }

        boolean disableComponentLP(String componentClassName) {
            boolean changed = enabledComponents.remove(componentClassName);
            changed |= disabledComponents.add(componentClassName);
            return changed;
        }

        boolean restoreComponentLP(String componentClassName) {
            boolean changed = enabledComponents.remove(componentClassName);
            changed |= disabledComponents.remove(componentClassName);
            return changed;
        }

        int currentEnabledStateLP(String componentName) {
            if (enabledComponents.contains(componentName)) {
                return COMPONENT_ENABLED_STATE_ENABLED;
            } else if (disabledComponents.contains(componentName)) {
                return COMPONENT_ENABLED_STATE_DISABLED;
            } else {
                return COMPONENT_ENABLED_STATE_DEFAULT;
            }
        }
    }

    /**
     * Settings data for a particular package we know about.
     */
    static final class PackageSetting extends PackageSettingBase {
        int userId;
        PackageParser.Package pkg;
        SharedUserSetting sharedUser;

        PackageSetting(String name, String realName, File codePath, File resourcePath,
                int pVersionCode, int pkgFlags) {
            super(name, realName, codePath, resourcePath, pVersionCode, pkgFlags);
        }

        @Override
        public String toString() {
            return "PackageSetting{"
                + Integer.toHexString(System.identityHashCode(this))
                + " " + name + "/" + userId + "}";
        }
    }

    /**
     * Settings data for a particular shared user ID we know about.
     */
    static final class SharedUserSetting extends GrantedPermissions {
        final String name;
        int userId;
        final HashSet<PackageSetting> packages = new HashSet<PackageSetting>();
        final PackageSignatures signatures = new PackageSignatures();

        SharedUserSetting(String _name, int _pkgFlags) {
            super(_pkgFlags);
            name = _name;
        }

        @Override
        public String toString() {
            return "SharedUserSetting{"
                + Integer.toHexString(System.identityHashCode(this))
                + " " + name + "/" + userId + "}";
        }
    }

    /**
     * Holds information about dynamic settings.
     */
    private static final class Settings {
        private final File mSettingsFilename;
        private final File mBackupSettingsFilename;
        private final File mPackageListFilename;
        private final HashMap<String, PackageSetting> mPackages =
                new HashMap<String, PackageSetting>();
        // List of replaced system applications
        final HashMap<String, PackageSetting> mDisabledSysPackages =
            new HashMap<String, PackageSetting>();

        // These are the last platform API version we were using for
        // the apps installed on internal and external storage.  It is
        // used to grant newer permissions one time during a system upgrade.
        int mInternalSdkPlatform;
        int mExternalSdkPlatform;
        
        // The user's preferred activities associated with particular intent
        // filters.
        private final IntentResolver<PreferredActivity, PreferredActivity> mPreferredActivities =
                    new IntentResolver<PreferredActivity, PreferredActivity>() {
            @Override
            protected String packageForFilter(PreferredActivity filter) {
                return filter.mActivity.getPackageName();
            }
            @Override
            protected void dumpFilter(PrintWriter out, String prefix,
                    PreferredActivity filter) {
                out.print(prefix); out.print(
                        Integer.toHexString(System.identityHashCode(filter)));
                        out.print(' ');
                        out.print(filter.mActivity.flattenToShortString());
                        out.print(" match=0x");
                        out.println( Integer.toHexString(filter.mMatch));
                if (filter.mSetComponents != null) {
                    out.print(prefix); out.println("  Selected from:");
                    for (int i=0; i<filter.mSetComponents.length; i++) {
                        out.print(prefix); out.print("    ");
                                out.println(filter.mSetComponents[i]);
                    }
                }
            }
        };
        private final HashMap<String, SharedUserSetting> mSharedUsers =
                new HashMap<String, SharedUserSetting>();
        private final ArrayList<Object> mUserIds = new ArrayList<Object>();
        private final SparseArray<Object> mOtherUserIds =
                new SparseArray<Object>();

        // For reading/writing settings file.
        private final ArrayList<Signature> mPastSignatures =
                new ArrayList<Signature>();

        // Mapping from permission names to info about them.
        final HashMap<String, BasePermission> mPermissions =
                new HashMap<String, BasePermission>();

        // Mapping from permission tree names to info about them.
        final HashMap<String, BasePermission> mPermissionTrees =
                new HashMap<String, BasePermission>();

        // Packages that have been uninstalled and still need their external
        // storage data deleted.
        final ArrayList<String> mPackagesToBeCleaned = new ArrayList<String>();
        
        // Packages that have been renamed since they were first installed.
        // Keys are the new names of the packages, values are the original
        // names.  The packages appear everwhere else under their original
        // names.
        final HashMap<String, String> mRenamedPackages = new HashMap<String, String>();
        
        private final StringBuilder mReadMessages = new StringBuilder();

        private static final class PendingPackage extends PackageSettingBase {
            final int sharedId;

            PendingPackage(String name, String realName, File codePath, File resourcePath,
                    int sharedId, int pVersionCode, int pkgFlags) {
                super(name, realName, codePath, resourcePath, pVersionCode, pkgFlags);
                this.sharedId = sharedId;
            }
        }
        private final ArrayList<PendingPackage> mPendingPackages
                = new ArrayList<PendingPackage>();

        Settings() {
            File dataDir = Environment.getDataDirectory();
            File systemDir = new File(dataDir, "system");
            // TODO(oam): This secure dir creation needs to be moved somewhere else (later)
            systemDir.mkdirs();
            FileUtils.setPermissions(systemDir.toString(),
                    FileUtils.S_IRWXU|FileUtils.S_IRWXG
                    |FileUtils.S_IROTH|FileUtils.S_IXOTH,
                    -1, -1);
            mSettingsFilename = new File(systemDir, "packages.xml");
            mBackupSettingsFilename = new File(systemDir, "packages-backup.xml");
            mPackageListFilename = new File(systemDir, "packages.list");
        }

        PackageSetting getPackageLP(PackageParser.Package pkg, PackageSetting origPackage,
                String realName, SharedUserSetting sharedUser, File codePath, File resourcePath,
                int pkgFlags, boolean create, boolean add) {
            final String name = pkg.packageName;
            PackageSetting p = getPackageLP(name, origPackage, realName, sharedUser, codePath,
                    resourcePath, pkg.mVersionCode, pkgFlags, create, add);
            return p;
        }

        PackageSetting peekPackageLP(String name) {
            return mPackages.get(name);
            /*
            PackageSetting p = mPackages.get(name);
            if (p != null && p.codePath.getPath().equals(codePath)) {
                return p;
            }
            return null;
            */
        }

        void setInstallStatus(String pkgName, int status) {
            PackageSetting p = mPackages.get(pkgName);
            if(p != null) {
                if(p.getInstallStatus() != status) {
                    p.setInstallStatus(status);
                }
            }
        }

        void setInstallerPackageName(String pkgName,
                String installerPkgName) {
            PackageSetting p = mPackages.get(pkgName);
            if(p != null) {
                p.setInstallerPackageName(installerPkgName);
            }
        }

        String getInstallerPackageName(String pkgName) {
            PackageSetting p = mPackages.get(pkgName);
            return (p == null) ? null : p.getInstallerPackageName();
        }

        int getInstallStatus(String pkgName) {
            PackageSetting p = mPackages.get(pkgName);
            if(p != null) {
                return p.getInstallStatus();
            }
            return -1;
        }

        SharedUserSetting getSharedUserLP(String name,
                int pkgFlags, boolean create) {
            SharedUserSetting s = mSharedUsers.get(name);
            if (s == null) {
                if (!create) {
                    return null;
                }
                s = new SharedUserSetting(name, pkgFlags);
                if (MULTIPLE_APPLICATION_UIDS) {
                    s.userId = newUserIdLP(s);
                } else {
                    s.userId = FIRST_APPLICATION_UID;
                }
                Log.i(TAG, "New shared user " + name + ": id=" + s.userId);
                // < 0 means we couldn't assign a userid; fall out and return
                // s, which is currently null
                if (s.userId >= 0) {
                    mSharedUsers.put(name, s);
                }
            }

            return s;
        }

        int disableSystemPackageLP(String name) {
            PackageSetting p = mPackages.get(name);
            if(p == null) {
                Log.w(TAG, "Package:"+name+" is not an installed package");
                return -1;
            }
            PackageSetting dp = mDisabledSysPackages.get(name);
            // always make sure the system package code and resource paths dont change
            if(dp == null) {
                if((p.pkg != null) && (p.pkg.applicationInfo != null)) {
                    p.pkg.applicationInfo.flags |= ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
                }
                mDisabledSysPackages.put(name, p);
            }
            return removePackageLP(name);
        }

        PackageSetting enableSystemPackageLP(String name) {
            PackageSetting p = mDisabledSysPackages.get(name);
            if(p == null) {
                Log.w(TAG, "Package:"+name+" is not disabled");
                return null;
            }
            // Reset flag in ApplicationInfo object
            if((p.pkg != null) && (p.pkg.applicationInfo != null)) {
                p.pkg.applicationInfo.flags &= ~ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
            }
            PackageSetting ret = addPackageLP(name, p.realName, p.codePath,
                    p.resourcePath, p.userId, p.versionCode, p.pkgFlags);
            mDisabledSysPackages.remove(name);
            return ret;
        }

        PackageSetting addPackageLP(String name, String realName, File codePath,
                File resourcePath, int uid, int vc, int pkgFlags) {
            PackageSetting p = mPackages.get(name);
            if (p != null) {
                if (p.userId == uid) {
                    return p;
                }
                reportSettingsProblem(Log.ERROR,
                        "Adding duplicate package, keeping first: " + name);
                return null;
            }
            p = new PackageSetting(name, realName, codePath, resourcePath, vc, pkgFlags);
            p.userId = uid;
            if (addUserIdLP(uid, p, name)) {
                mPackages.put(name, p);
                return p;
            }
            return null;
        }

        SharedUserSetting addSharedUserLP(String name, int uid, int pkgFlags) {
            SharedUserSetting s = mSharedUsers.get(name);
            if (s != null) {
                if (s.userId == uid) {
                    return s;
                }
                reportSettingsProblem(Log.ERROR,
                        "Adding duplicate shared user, keeping first: " + name);
                return null;
            }
            s = new SharedUserSetting(name, pkgFlags);
            s.userId = uid;
            if (addUserIdLP(uid, s, name)) {
                mSharedUsers.put(name, s);
                return s;
            }
            return null;
        }

        // Transfer ownership of permissions from one package to another.
        private void transferPermissions(String origPkg, String newPkg) {
            // Transfer ownership of permissions to the new package.
            for (int i=0; i<2; i++) {
                HashMap<String, BasePermission> permissions =
                        i == 0 ? mPermissionTrees : mPermissions;
                for (BasePermission bp : permissions.values()) {
                    if (origPkg.equals(bp.sourcePackage)) {
                        if (DEBUG_UPGRADE) Log.v(TAG,
                                "Moving permission " + bp.name
                                + " from pkg " + bp.sourcePackage
                                + " to " + newPkg);
                        bp.sourcePackage = newPkg;
                        bp.packageSetting = null;
                        bp.perm = null;
                        if (bp.pendingInfo != null) {
                            bp.pendingInfo.packageName = newPkg;
                        }
                        bp.uid = 0;
                        bp.gids = null;
                    }
                }
            }
        }
        
        private PackageSetting getPackageLP(String name, PackageSetting origPackage,
                String realName, SharedUserSetting sharedUser, File codePath, File resourcePath,
                int vc, int pkgFlags, boolean create, boolean add) {
            PackageSetting p = mPackages.get(name);
            if (p != null) {
                if (!p.codePath.equals(codePath)) {
                    // Check to see if its a disabled system app
                    if((p != null) && ((p.pkgFlags & ApplicationInfo.FLAG_SYSTEM) != 0)) {
                        // This is an updated system app with versions in both system
                        // and data partition. Just let the most recent version
                        // take precedence.
                        Slog.w(TAG, "Trying to update system app code path from " +
                                p.codePathString + " to " + codePath.toString());
                    } else {
                        // Just a change in the code path is not an issue, but
                        // let's log a message about it.
                        Slog.i(TAG, "Package " + name + " codePath changed from " + p.codePath
                                + " to " + codePath + "; Retaining data and using new");
                    }
                }
                if (p.sharedUser != sharedUser) {
                    reportSettingsProblem(Log.WARN,
                            "Package " + name + " shared user changed from "
                            + (p.sharedUser != null ? p.sharedUser.name : "<nothing>")
                            + " to "
                            + (sharedUser != null ? sharedUser.name : "<nothing>")
                            + "; replacing with new");
                    p = null;
                } else {
                    if ((pkgFlags&ApplicationInfo.FLAG_SYSTEM) != 0) {
                        // If what we are scanning is a system package, then
                        // make it so, regardless of whether it was previously
                        // installed only in the data partition.
                        p.pkgFlags |= ApplicationInfo.FLAG_SYSTEM;
                    }
                }
            }
            if (p == null) {
                // Create a new PackageSettings entry. this can end up here because
                // of code path mismatch or user id mismatch of an updated system partition
                if (!create) {
                    return null;
                }
                if (origPackage != null) {
                    // We are consuming the data from an existing package.
                    p = new PackageSetting(origPackage.name, name, codePath,
                            resourcePath, vc, pkgFlags);
                    if (DEBUG_UPGRADE) Log.v(TAG, "Package " + name
                            + " is adopting original package " + origPackage.name);
                    // Note that we will retain the new package's signature so
                    // that we can keep its data.
                    PackageSignatures s = p.signatures;
                    p.copyFrom(origPackage);
                    p.signatures = s;
                    p.sharedUser = origPackage.sharedUser;
                    p.userId = origPackage.userId;
                    p.origPackage = origPackage;
                    mRenamedPackages.put(name, origPackage.name);
                    name = origPackage.name;
                    // Update new package state.
                    p.setTimeStamp(codePath.lastModified());
                } else {
                    p = new PackageSetting(name, realName, codePath, resourcePath, vc, pkgFlags);
                    p.setTimeStamp(codePath.lastModified());
                    p.sharedUser = sharedUser;
                    if (sharedUser != null) {
                        p.userId = sharedUser.userId;
                    } else if (MULTIPLE_APPLICATION_UIDS) {
                        // Clone the setting here for disabled system packages
                        PackageSetting dis = mDisabledSysPackages.get(name);
                        if (dis != null) {
                            // For disabled packages a new setting is created
                            // from the existing user id. This still has to be
                            // added to list of user id's
                            // Copy signatures from previous setting
                            if (dis.signatures.mSignatures != null) {
                                p.signatures.mSignatures = dis.signatures.mSignatures.clone();
                            }
                            p.userId = dis.userId;
                            // Clone permissions
                            p.grantedPermissions = new HashSet<String>(dis.grantedPermissions);
                            // Clone component info
                            p.disabledComponents = new HashSet<String>(dis.disabledComponents);
                            p.enabledComponents = new HashSet<String>(dis.enabledComponents);
                            // Add new setting to list of user ids
                            addUserIdLP(p.userId, p, name);
                        } else {
                            // Assign new user id
                            p.userId = newUserIdLP(p);
                        }
                    } else {
                        p.userId = FIRST_APPLICATION_UID;
                    }
                }
                if (p.userId < 0) {
                    reportSettingsProblem(Log.WARN,
                            "Package " + name + " could not be assigned a valid uid");
                    return null;
                }
                if (add) {
                    // Finish adding new package by adding it and updating shared
                    // user preferences
                    addPackageSettingLP(p, name, sharedUser);
                }
            }
            return p;
        }

        private void insertPackageSettingLP(PackageSetting p, PackageParser.Package pkg) {
            p.pkg = pkg;
            String codePath = pkg.applicationInfo.sourceDir;
            String resourcePath = pkg.applicationInfo.publicSourceDir;
            // Update code path if needed
            if (!codePath.equalsIgnoreCase(p.codePathString)) {
                Slog.w(TAG, "Code path for pkg : " + p.pkg.packageName +
                        " changing from " + p.codePathString + " to " + codePath);
                p.codePath = new File(codePath);
                p.codePathString = codePath;
            }
            //Update resource path if needed
            if (!resourcePath.equalsIgnoreCase(p.resourcePathString)) {
                Slog.w(TAG, "Resource path for pkg : " + p.pkg.packageName +
                        " changing from " + p.resourcePathString + " to " + resourcePath);
                p.resourcePath = new File(resourcePath);
                p.resourcePathString = resourcePath;
            }
            // Update version code if needed
             if (pkg.mVersionCode != p.versionCode) {
                p.versionCode = pkg.mVersionCode;
            }
             // Update signatures if needed.
             if (p.signatures.mSignatures == null) {
                 p.signatures.assignSignatures(pkg.mSignatures);
             }
             // If this app defines a shared user id initialize
             // the shared user signatures as well.
             if (p.sharedUser != null && p.sharedUser.signatures.mSignatures == null) {
                 p.sharedUser.signatures.assignSignatures(pkg.mSignatures);
             }
            addPackageSettingLP(p, pkg.packageName, p.sharedUser);
        }

        // Utility method that adds a PackageSetting to mPackages and
        // completes updating the shared user attributes
        private void addPackageSettingLP(PackageSetting p, String name,
                SharedUserSetting sharedUser) {
            mPackages.put(name, p);
            if (sharedUser != null) {
                if (p.sharedUser != null && p.sharedUser != sharedUser) {
                    reportSettingsProblem(Log.ERROR,
                            "Package " + p.name + " was user "
                            + p.sharedUser + " but is now " + sharedUser
                            + "; I am not changing its files so it will probably fail!");
                    p.sharedUser.packages.remove(p);
                } else if (p.userId != sharedUser.userId) {
                    reportSettingsProblem(Log.ERROR,
                        "Package " + p.name + " was user id " + p.userId
                        + " but is now user " + sharedUser
                        + " with id " + sharedUser.userId
                        + "; I am not changing its files so it will probably fail!");
                }

                sharedUser.packages.add(p);
                p.sharedUser = sharedUser;
                p.userId = sharedUser.userId;
            }
        }

        /*
         * Update the shared user setting when a package using
         * specifying the shared user id is removed. The gids
         * associated with each permission of the deleted package
         * are removed from the shared user's gid list only if its
         * not in use by other permissions of packages in the
         * shared user setting.
         */
        private void updateSharedUserPermsLP(PackageSetting deletedPs, int[] globalGids) {
            if ( (deletedPs == null) || (deletedPs.pkg == null)) {
                Slog.i(TAG, "Trying to update info for null package. Just ignoring");
                return;
            }
            // No sharedUserId
            if (deletedPs.sharedUser == null) {
                return;
            }
            SharedUserSetting sus = deletedPs.sharedUser;
            // Update permissions
            for (String eachPerm: deletedPs.pkg.requestedPermissions) {
                boolean used = false;
                if (!sus.grantedPermissions.contains (eachPerm)) {
                    continue;
                }
                for (PackageSetting pkg:sus.packages) {
                    if (pkg.pkg != null &&
                            !pkg.pkg.packageName.equals(deletedPs.pkg.packageName) &&
                            pkg.pkg.requestedPermissions.contains(eachPerm)) {
                        used = true;
                        break;
                    }
                }
                if (!used) {
                    // can safely delete this permission from list
                    sus.grantedPermissions.remove(eachPerm);
                }
            }
            // Update gids
            int newGids[] = globalGids;
            for (String eachPerm : sus.grantedPermissions) {
                BasePermission bp = mPermissions.get(eachPerm);
                if (bp != null) {
                    newGids = appendInts(newGids, bp.gids);
                }
            }
            sus.gids = newGids;
        }

        private int removePackageLP(String name) {
            PackageSetting p = mPackages.get(name);
            if (p != null) {
                mPackages.remove(name);
                if (p.sharedUser != null) {
                    p.sharedUser.packages.remove(p);
                    if (p.sharedUser.packages.size() == 0) {
                        mSharedUsers.remove(p.sharedUser.name);
                        removeUserIdLP(p.sharedUser.userId);
                        return p.sharedUser.userId;
                    }
                } else {
                    removeUserIdLP(p.userId);
                    return p.userId;
                }
            }
            return -1;
        }

        private boolean addUserIdLP(int uid, Object obj, Object name) {
            if (uid >= FIRST_APPLICATION_UID + MAX_APPLICATION_UIDS) {
                return false;
            }

            if (uid >= FIRST_APPLICATION_UID) {
                int N = mUserIds.size();
                final int index = uid - FIRST_APPLICATION_UID;
                while (index >= N) {
                    mUserIds.add(null);
                    N++;
                }
                if (mUserIds.get(index) != null) {
                    reportSettingsProblem(Log.ERROR,
                            "Adding duplicate user id: " + uid
                            + " name=" + name);
                    return false;
                }
                mUserIds.set(index, obj);
            } else {
                if (mOtherUserIds.get(uid) != null) {
                    reportSettingsProblem(Log.ERROR,
                            "Adding duplicate shared id: " + uid
                            + " name=" + name);
                    return false;
                }
                mOtherUserIds.put(uid, obj);
            }
            return true;
        }

        public Object getUserIdLP(int uid) {
            if (uid >= FIRST_APPLICATION_UID) {
                int N = mUserIds.size();
                final int index = uid - FIRST_APPLICATION_UID;
                return index < N ? mUserIds.get(index) : null;
            } else {
                return mOtherUserIds.get(uid);
            }
        }

        private Set<String> findPackagesWithFlag(int flag) {
            Set<String> ret = new HashSet<String>();
            for (PackageSetting ps : mPackages.values()) {
                // Has to match atleast all the flag bits set on flag
                if ((ps.pkgFlags & flag) == flag) {
                    ret.add(ps.name);
                }
            }
            return ret;
        }

        private void removeUserIdLP(int uid) {
            if (uid >= FIRST_APPLICATION_UID) {
                int N = mUserIds.size();
                final int index = uid - FIRST_APPLICATION_UID;
                if (index < N) mUserIds.set(index, null);
            } else {
                mOtherUserIds.remove(uid);
            }
        }

        void writeLP() {
            //Debug.startMethodTracing("/data/system/packageprof", 8 * 1024 * 1024);

            // Keep the old settings around until we know the new ones have
            // been successfully written.
            if (mSettingsFilename.exists()) {
                // Presence of backup settings file indicates that we failed
                // to persist settings earlier. So preserve the older
                // backup for future reference since the current settings
                // might have been corrupted.
                if (!mBackupSettingsFilename.exists()) {
                    if (!mSettingsFilename.renameTo(mBackupSettingsFilename)) {
                        Slog.w(TAG, "Unable to backup package manager settings, current changes will be lost at reboot");
                        return;
                    }
                } else {
                    mSettingsFilename.delete();
                    Slog.w(TAG, "Preserving older settings backup");
                }
            }

            mPastSignatures.clear();

            try {
                FileOutputStream str = new FileOutputStream(mSettingsFilename);

                //XmlSerializer serializer = XmlUtils.serializerInstance();
                XmlSerializer serializer = new FastXmlSerializer();
                serializer.setOutput(str, "utf-8");
                serializer.startDocument(null, true);
                serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);

                serializer.startTag(null, "packages");

                serializer.startTag(null, "last-platform-version");
                serializer.attribute(null, "internal", Integer.toString(mInternalSdkPlatform));
                serializer.attribute(null, "external", Integer.toString(mExternalSdkPlatform));
                serializer.endTag(null, "last-platform-version");
                
                serializer.startTag(null, "permission-trees");
                for (BasePermission bp : mPermissionTrees.values()) {
                    writePermission(serializer, bp);
                }
                serializer.endTag(null, "permission-trees");

                serializer.startTag(null, "permissions");
                for (BasePermission bp : mPermissions.values()) {
                    writePermission(serializer, bp);
                }
                serializer.endTag(null, "permissions");

                for (PackageSetting pkg : mPackages.values()) {
                    writePackage(serializer, pkg);
                }

                for (PackageSetting pkg : mDisabledSysPackages.values()) {
                    writeDisabledSysPackage(serializer, pkg);
                }

                serializer.startTag(null, "preferred-activities");
                for (PreferredActivity pa : mPreferredActivities.filterSet()) {
                    serializer.startTag(null, "item");
                    pa.writeToXml(serializer);
                    serializer.endTag(null, "item");
                }
                serializer.endTag(null, "preferred-activities");

                for (SharedUserSetting usr : mSharedUsers.values()) {
                    serializer.startTag(null, "shared-user");
                    serializer.attribute(null, "name", usr.name);
                    serializer.attribute(null, "userId",
                            Integer.toString(usr.userId));
                    usr.signatures.writeXml(serializer, "sigs", mPastSignatures);
                    serializer.startTag(null, "perms");
                    for (String name : usr.grantedPermissions) {
                        serializer.startTag(null, "item");
                        serializer.attribute(null, "name", name);
                        serializer.endTag(null, "item");
                    }
                    serializer.endTag(null, "perms");
                    serializer.endTag(null, "shared-user");
                }

                if (mPackagesToBeCleaned.size() > 0) {
                    for (int i=0; i<mPackagesToBeCleaned.size(); i++) {
                        serializer.startTag(null, "cleaning-package");
                        serializer.attribute(null, "name", mPackagesToBeCleaned.get(i));
                        serializer.endTag(null, "cleaning-package");
                    }
                }
                
                if (mRenamedPackages.size() > 0) {
                    for (HashMap.Entry<String, String> e : mRenamedPackages.entrySet()) {
                        serializer.startTag(null, "renamed-package");
                        serializer.attribute(null, "new", e.getKey());
                        serializer.attribute(null, "old", e.getValue());
                        serializer.endTag(null, "renamed-package");
                    }
                }
                
                serializer.endTag(null, "packages");

                serializer.endDocument();

                str.flush();
                str.close();

                // New settings successfully written, old ones are no longer
                // needed.
                mBackupSettingsFilename.delete();
                FileUtils.setPermissions(mSettingsFilename.toString(),
                        FileUtils.S_IRUSR|FileUtils.S_IWUSR
                        |FileUtils.S_IRGRP|FileUtils.S_IWGRP
                        |FileUtils.S_IROTH,
                        -1, -1);

                // Write package list file now, use a JournaledFile.
                //
                File tempFile = new File(mPackageListFilename.toString() + ".tmp");
                JournaledFile journal = new JournaledFile(mPackageListFilename, tempFile);

                str = new FileOutputStream(journal.chooseForWrite());
                try {
                    StringBuilder sb = new StringBuilder();
                    for (PackageSetting pkg : mPackages.values()) {
                        ApplicationInfo ai = pkg.pkg.applicationInfo;
                        String  dataPath = ai.dataDir;
                        boolean isDebug  = (ai.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;

                        // Avoid any application that has a space in its path
                        // or that is handled by the system.
                        if (dataPath.indexOf(" ") >= 0 || ai.uid <= Process.FIRST_APPLICATION_UID)
                            continue;

                        // we store on each line the following information for now:
                        //
                        // pkgName    - package name
                        // userId     - application-specific user id
                        // debugFlag  - 0 or 1 if the package is debuggable.
                        // dataPath   - path to package's data path
                        //
                        // NOTE: We prefer not to expose all ApplicationInfo flags for now.
                        //
                        // DO NOT MODIFY THIS FORMAT UNLESS YOU CAN ALSO MODIFY ITS USERS
                        // FROM NATIVE CODE. AT THE MOMENT, LOOK AT THE FOLLOWING SOURCES:
                        //   system/core/run-as/run-as.c
                        //
                        sb.setLength(0);
                        sb.append(ai.packageName);
                        sb.append(" ");
                        sb.append((int)ai.uid);
                        sb.append(isDebug ? " 1 " : " 0 ");
                        sb.append(dataPath);
                        sb.append("\n");
                        str.write(sb.toString().getBytes());
                    }
                    str.flush();
                    str.close();
                    journal.commit();
                }
                catch (Exception  e) {
                    journal.rollback();
                }

                FileUtils.setPermissions(mPackageListFilename.toString(),
                        FileUtils.S_IRUSR|FileUtils.S_IWUSR
                        |FileUtils.S_IRGRP|FileUtils.S_IWGRP
                        |FileUtils.S_IROTH,
                        -1, -1);

                return;

            } catch(XmlPullParserException e) {
                Slog.w(TAG, "Unable to write package manager settings, current changes will be lost at reboot", e);
            } catch(java.io.IOException e) {
                Slog.w(TAG, "Unable to write package manager settings, current changes will be lost at reboot", e);
            }
            // Clean up partially written files
            if (mSettingsFilename.exists()) {
                if (!mSettingsFilename.delete()) {
                    Log.i(TAG, "Failed to clean up mangled file: " + mSettingsFilename);
                }
            }
            //Debug.stopMethodTracing();
        }

        void writeDisabledSysPackage(XmlSerializer serializer, final PackageSetting pkg)
                throws java.io.IOException {
            serializer.startTag(null, "updated-package");
            serializer.attribute(null, "name", pkg.name);
            if (pkg.realName != null) {
                serializer.attribute(null, "realName", pkg.realName);
            }
            serializer.attribute(null, "codePath", pkg.codePathString);
            serializer.attribute(null, "ts", pkg.getTimeStampStr());
            serializer.attribute(null, "version", String.valueOf(pkg.versionCode));
            if (!pkg.resourcePathString.equals(pkg.codePathString)) {
                serializer.attribute(null, "resourcePath", pkg.resourcePathString);
            }
            if (pkg.sharedUser == null) {
                serializer.attribute(null, "userId",
                        Integer.toString(pkg.userId));
            } else {
                serializer.attribute(null, "sharedUserId",
                        Integer.toString(pkg.userId));
            }
            serializer.startTag(null, "perms");
            if (pkg.sharedUser == null) {
                // If this is a shared user, the permissions will
                // be written there.  We still need to write an
                // empty permissions list so permissionsFixed will
                // be set.
                for (final String name : pkg.grantedPermissions) {
                    BasePermission bp = mPermissions.get(name);
                    if (bp != null) {
                        // We only need to write signature or system permissions but this wont
                        // match the semantics of grantedPermissions. So write all permissions.
                        serializer.startTag(null, "item");
                        serializer.attribute(null, "name", name);
                        serializer.endTag(null, "item");
                    }
                }
            }
            serializer.endTag(null, "perms");
            serializer.endTag(null, "updated-package");
        }

        void writePackage(XmlSerializer serializer, final PackageSetting pkg)
                throws java.io.IOException {
            serializer.startTag(null, "package");
            serializer.attribute(null, "name", pkg.name);
            if (pkg.realName != null) {
                serializer.attribute(null, "realName", pkg.realName);
            }
            serializer.attribute(null, "codePath", pkg.codePathString);
            if (!pkg.resourcePathString.equals(pkg.codePathString)) {
                serializer.attribute(null, "resourcePath", pkg.resourcePathString);
            }
            serializer.attribute(null, "flags",
                    Integer.toString(pkg.pkgFlags));
            serializer.attribute(null, "ts", pkg.getTimeStampStr());
            serializer.attribute(null, "version", String.valueOf(pkg.versionCode));
            if (pkg.sharedUser == null) {
                serializer.attribute(null, "userId",
                        Integer.toString(pkg.userId));
            } else {
                serializer.attribute(null, "sharedUserId",
                        Integer.toString(pkg.userId));
            }
            if (pkg.uidError) {
                serializer.attribute(null, "uidError", "true");
            }
            if (pkg.enabled != COMPONENT_ENABLED_STATE_DEFAULT) {
                serializer.attribute(null, "enabled",
                        pkg.enabled == COMPONENT_ENABLED_STATE_ENABLED
                        ? "true" : "false");
            }
            if(pkg.installStatus == PKG_INSTALL_INCOMPLETE) {
                serializer.attribute(null, "installStatus", "false");
            }
            if (pkg.installerPackageName != null) {
                serializer.attribute(null, "installer", pkg.installerPackageName);
            }
            pkg.signatures.writeXml(serializer, "sigs", mPastSignatures);
            if ((pkg.pkgFlags&ApplicationInfo.FLAG_SYSTEM) == 0) {
                serializer.startTag(null, "perms");
                if (pkg.sharedUser == null) {
                    // If this is a shared user, the permissions will
                    // be written there.  We still need to write an
                    // empty permissions list so permissionsFixed will
                    // be set.
                    for (final String name : pkg.grantedPermissions) {
                        serializer.startTag(null, "item");
                        serializer.attribute(null, "name", name);
                        serializer.endTag(null, "item");
                    }
                }
                serializer.endTag(null, "perms");
            }
            if (pkg.disabledComponents.size() > 0) {
                serializer.startTag(null, "disabled-components");
                for (final String name : pkg.disabledComponents) {
                    serializer.startTag(null, "item");
                    serializer.attribute(null, "name", name);
                    serializer.endTag(null, "item");
                }
                serializer.endTag(null, "disabled-components");
            }
            if (pkg.enabledComponents.size() > 0) {
                serializer.startTag(null, "enabled-components");
                for (final String name : pkg.enabledComponents) {
                    serializer.startTag(null, "item");
                    serializer.attribute(null, "name", name);
                    serializer.endTag(null, "item");
                }
                serializer.endTag(null, "enabled-components");
            }

            serializer.endTag(null, "package");
        }

        void writePermission(XmlSerializer serializer, BasePermission bp)
                throws XmlPullParserException, java.io.IOException {
            if (bp.type != BasePermission.TYPE_BUILTIN
                    && bp.sourcePackage != null) {
                serializer.startTag(null, "item");
                serializer.attribute(null, "name", bp.name);
                serializer.attribute(null, "package", bp.sourcePackage);
                if (bp.protectionLevel !=
                        PermissionInfo.PROTECTION_NORMAL) {
                    serializer.attribute(null, "protection",
                            Integer.toString(bp.protectionLevel));
                }
                if (DEBUG_SETTINGS) Log.v(TAG,
                        "Writing perm: name=" + bp.name + " type=" + bp.type);
                if (bp.type == BasePermission.TYPE_DYNAMIC) {
                    PermissionInfo pi = bp.perm != null ? bp.perm.info
                            : bp.pendingInfo;
                    if (pi != null) {
                        serializer.attribute(null, "type", "dynamic");
                        if (pi.icon != 0) {
                            serializer.attribute(null, "icon",
                                    Integer.toString(pi.icon));
                        }
                        if (pi.nonLocalizedLabel != null) {
                            serializer.attribute(null, "label",
                                    pi.nonLocalizedLabel.toString());
                        }
                    }
                }
                serializer.endTag(null, "item");
            }
        }

        String getReadMessagesLP() {
            return mReadMessages.toString();
        }

        ArrayList<PackageSetting> getListOfIncompleteInstallPackages() {
            HashSet<String> kList = new HashSet<String>(mPackages.keySet());
            Iterator<String> its = kList.iterator();
            ArrayList<PackageSetting> ret = new ArrayList<PackageSetting>();
            while(its.hasNext()) {
                String key = its.next();
                PackageSetting ps = mPackages.get(key);
                if(ps.getInstallStatus() == PKG_INSTALL_INCOMPLETE) {
                    ret.add(ps);
                }
            }
            return ret;
        }

        boolean readLP() {
            FileInputStream str = null;
            if (mBackupSettingsFilename.exists()) {
                try {
                    str = new FileInputStream(mBackupSettingsFilename);
                    mReadMessages.append("Reading from backup settings file\n");
                    Log.i(TAG, "Reading from backup settings file!");
                    if (mSettingsFilename.exists()) {
                        // If both the backup and settings file exist, we
                        // ignore the settings since it might have been
                        // corrupted.
                        Slog.w(TAG, "Cleaning up settings file " + mSettingsFilename);
                        mSettingsFilename.delete();
                    }
                } catch (java.io.IOException e) {
                    // We'll try for the normal settings file.
                }
            }

            mPastSignatures.clear();

            try {
                if (str == null) {
                    if (!mSettingsFilename.exists()) {
                        mReadMessages.append("No settings file found\n");
                        Slog.i(TAG, "No current settings file!");
                        return false;
                    }
                    str = new FileInputStream(mSettingsFilename);
                }
                XmlPullParser parser = Xml.newPullParser();
                parser.setInput(str, null);

                int type;
                while ((type=parser.next()) != XmlPullParser.START_TAG
                           && type != XmlPullParser.END_DOCUMENT) {
                    ;
                }

                if (type != XmlPullParser.START_TAG) {
                    mReadMessages.append("No start tag found in settings file\n");
                    Slog.e(TAG, "No start tag found in package manager settings");
                    return false;
                }

                int outerDepth = parser.getDepth();
                while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
                       && (type != XmlPullParser.END_TAG
                               || parser.getDepth() > outerDepth)) {
                    if (type == XmlPullParser.END_TAG
                            || type == XmlPullParser.TEXT) {
                        continue;
                    }

                    String tagName = parser.getName();
                    if (tagName.equals("package")) {
                        readPackageLP(parser);
                    } else if (tagName.equals("permissions")) {
                        readPermissionsLP(mPermissions, parser);
                    } else if (tagName.equals("permission-trees")) {
                        readPermissionsLP(mPermissionTrees, parser);
                    } else if (tagName.equals("shared-user")) {
                        readSharedUserLP(parser);
                    } else if (tagName.equals("preferred-packages")) {
                        // no longer used.
                    } else if (tagName.equals("preferred-activities")) {
                        readPreferredActivitiesLP(parser);
                    } else if(tagName.equals("updated-package")) {
                        readDisabledSysPackageLP(parser);
                    } else if (tagName.equals("cleaning-package")) {
                        String name = parser.getAttributeValue(null, "name");
                        if (name != null) {
                            mPackagesToBeCleaned.add(name);
                        }
                    } else if (tagName.equals("renamed-package")) {
                        String nname = parser.getAttributeValue(null, "new");
                        String oname = parser.getAttributeValue(null, "old");
                        if (nname != null && oname != null) {
                            mRenamedPackages.put(nname, oname);
                        }
                    } else if (tagName.equals("last-platform-version")) {
                        mInternalSdkPlatform = mExternalSdkPlatform = 0;
                        try {
                            String internal = parser.getAttributeValue(null, "internal");
                            if (internal != null) {
                                mInternalSdkPlatform = Integer.parseInt(internal);
                            }
                            String external = parser.getAttributeValue(null, "external");
                            if (external != null) {
                                mExternalSdkPlatform = Integer.parseInt(external);
                            }
                        } catch (NumberFormatException e) {
                        }
                    } else {
                        Slog.w(TAG, "Unknown element under <packages>: "
                              + parser.getName());
                        XmlUtils.skipCurrentTag(parser);
                    }
                }

                str.close();

            } catch(XmlPullParserException e) {
                mReadMessages.append("Error reading: " + e.toString());
                Slog.e(TAG, "Error reading package manager settings", e);

            } catch(java.io.IOException e) {
                mReadMessages.append("Error reading: " + e.toString());
                Slog.e(TAG, "Error reading package manager settings", e);

            }

            int N = mPendingPackages.size();
            for (int i=0; i<N; i++) {
                final PendingPackage pp = mPendingPackages.get(i);
                Object idObj = getUserIdLP(pp.sharedId);
                if (idObj != null && idObj instanceof SharedUserSetting) {
                    PackageSetting p = getPackageLP(pp.name, null, pp.realName,
                            (SharedUserSetting)idObj, pp.codePath, pp.resourcePath,
                            pp.versionCode, pp.pkgFlags, true, true);
                    if (p == null) {
                        Slog.w(TAG, "Unable to create application package for "
                                + pp.name);
                        continue;
                    }
                    p.copyFrom(pp);
                } else if (idObj != null) {
                    String msg = "Bad package setting: package " + pp.name
                            + " has shared uid " + pp.sharedId
                            + " that is not a shared uid\n";
                    mReadMessages.append(msg);
                    Slog.e(TAG, msg);
                } else {
                    String msg = "Bad package setting: package " + pp.name
                            + " has shared uid " + pp.sharedId
                            + " that is not defined\n";
                    mReadMessages.append(msg);
                    Slog.e(TAG, msg);
                }
            }
            mPendingPackages.clear();

            mReadMessages.append("Read completed successfully: "
                    + mPackages.size() + " packages, "
                    + mSharedUsers.size() + " shared uids\n");

            return true;
        }

        private int readInt(XmlPullParser parser, String ns, String name,
                int defValue) {
            String v = parser.getAttributeValue(ns, name);
            try {
                if (v == null) {
                    return defValue;
                }
                return Integer.parseInt(v);
            } catch (NumberFormatException e) {
                reportSettingsProblem(Log.WARN,
                        "Error in package manager settings: attribute " +
                        name + " has bad integer value " + v + " at "
                        + parser.getPositionDescription());
            }
            return defValue;
        }

        private void readPermissionsLP(HashMap<String, BasePermission> out,
                XmlPullParser parser)
                throws IOException, XmlPullParserException {
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
                if (tagName.equals("item")) {
                    String name = parser.getAttributeValue(null, "name");
                    String sourcePackage = parser.getAttributeValue(null, "package");
                    String ptype = parser.getAttributeValue(null, "type");
                    if (name != null && sourcePackage != null) {
                        boolean dynamic = "dynamic".equals(ptype);
                        BasePermission bp = new BasePermission(name, sourcePackage,
                                dynamic
                                ? BasePermission.TYPE_DYNAMIC
                                : BasePermission.TYPE_NORMAL);
                        bp.protectionLevel = readInt(parser, null, "protection",
                                PermissionInfo.PROTECTION_NORMAL);
                        if (dynamic) {
                            PermissionInfo pi = new PermissionInfo();
                            pi.packageName = sourcePackage.intern();
                            pi.name = name.intern();
                            pi.icon = readInt(parser, null, "icon", 0);
                            pi.nonLocalizedLabel = parser.getAttributeValue(
                                    null, "label");
                            pi.protectionLevel = bp.protectionLevel;
                            bp.pendingInfo = pi;
                        }
                        out.put(bp.name, bp);
                    } else {
                        reportSettingsProblem(Log.WARN,
                                "Error in package manager settings: permissions has"
                                + " no name at " + parser.getPositionDescription());
                    }
                } else {
                    reportSettingsProblem(Log.WARN,
                            "Unknown element reading permissions: "
                            + parser.getName() + " at "
                            + parser.getPositionDescription());
                }
                XmlUtils.skipCurrentTag(parser);
            }
        }

        private void readDisabledSysPackageLP(XmlPullParser parser)
                throws XmlPullParserException, IOException {
            String name = parser.getAttributeValue(null, "name");
            String realName = parser.getAttributeValue(null, "realName");
            String codePathStr = parser.getAttributeValue(null, "codePath");
            String resourcePathStr = parser.getAttributeValue(null, "resourcePath");
            if (resourcePathStr == null) {
                resourcePathStr = codePathStr;
            }
            String version = parser.getAttributeValue(null, "version");
            int versionCode = 0;
            if (version != null) {
                try {
                    versionCode = Integer.parseInt(version);
                } catch (NumberFormatException e) {
                }
            }

            int pkgFlags = 0;
            pkgFlags |= ApplicationInfo.FLAG_SYSTEM;
            PackageSetting ps = new PackageSetting(name, realName,
                    new File(codePathStr),
                    new File(resourcePathStr), versionCode, pkgFlags);
            String timeStampStr = parser.getAttributeValue(null, "ts");
            if (timeStampStr != null) {
                try {
                    long timeStamp = Long.parseLong(timeStampStr);
                    ps.setTimeStamp(timeStamp, timeStampStr);
                } catch (NumberFormatException e) {
                }
            }
            String idStr = parser.getAttributeValue(null, "userId");
            ps.userId = idStr != null ? Integer.parseInt(idStr) : 0;
            if(ps.userId <= 0) {
                String sharedIdStr = parser.getAttributeValue(null, "sharedUserId");
                ps.userId = sharedIdStr != null ? Integer.parseInt(sharedIdStr) : 0;
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
                if (tagName.equals("perms")) {
                    readGrantedPermissionsLP(parser,
                            ps.grantedPermissions);
                } else {
                    reportSettingsProblem(Log.WARN,
                            "Unknown element under <updated-package>: "
                            + parser.getName());
                    XmlUtils.skipCurrentTag(parser);
                }
            }
            mDisabledSysPackages.put(name, ps);
        }

        private void readPackageLP(XmlPullParser parser)
                throws XmlPullParserException, IOException {
            String name = null;
            String realName = null;
            String idStr = null;
            String sharedIdStr = null;
            String codePathStr = null;
            String resourcePathStr = null;
            String systemStr = null;
            String installerPackageName = null;
            String uidError = null;
            int pkgFlags = 0;
            String timeStampStr;
            long timeStamp = 0;
            PackageSettingBase packageSetting = null;
            String version = null;
            int versionCode = 0;
            try {
                name = parser.getAttributeValue(null, "name");
                realName = parser.getAttributeValue(null, "realName");
                idStr = parser.getAttributeValue(null, "userId");
                uidError = parser.getAttributeValue(null, "uidError");
                sharedIdStr = parser.getAttributeValue(null, "sharedUserId");
                codePathStr = parser.getAttributeValue(null, "codePath");
                resourcePathStr = parser.getAttributeValue(null, "resourcePath");
                version = parser.getAttributeValue(null, "version");
                if (version != null) {
                    try {
                        versionCode = Integer.parseInt(version);
                    } catch (NumberFormatException e) {
                    }
                }
                installerPackageName = parser.getAttributeValue(null, "installer");

                systemStr = parser.getAttributeValue(null, "flags");
                if (systemStr != null) {
                    try {
                        pkgFlags = Integer.parseInt(systemStr);
                    } catch (NumberFormatException e) {
                    }
                } else {
                    // For backward compatibility
                    systemStr = parser.getAttributeValue(null, "system");
                    if (systemStr != null) {
                        pkgFlags |= ("true".equalsIgnoreCase(systemStr)) ? ApplicationInfo.FLAG_SYSTEM : 0;
                    } else {
                        // Old settings that don't specify system...  just treat
                        // them as system, good enough.
                        pkgFlags |= ApplicationInfo.FLAG_SYSTEM;
                    }
                }
                timeStampStr = parser.getAttributeValue(null, "ts");
                if (timeStampStr != null) {
                    try {
                        timeStamp = Long.parseLong(timeStampStr);
                    } catch (NumberFormatException e) {
                    }
                }
                if (DEBUG_SETTINGS) Log.v(TAG, "Reading package: " + name
                        + " userId=" + idStr + " sharedUserId=" + sharedIdStr);
                int userId = idStr != null ? Integer.parseInt(idStr) : 0;
                if (resourcePathStr == null) {
                    resourcePathStr = codePathStr;
                }
                if (realName != null) {
                    realName = realName.intern();
                }
                if (name == null) {
                    reportSettingsProblem(Log.WARN,
                            "Error in package manager settings: <package> has no name at "
                            + parser.getPositionDescription());
                } else if (codePathStr == null) {
                    reportSettingsProblem(Log.WARN,
                            "Error in package manager settings: <package> has no codePath at "
                            + parser.getPositionDescription());
                } else if (userId > 0) {
                    packageSetting = addPackageLP(name.intern(), realName,
                            new File(codePathStr), new File(resourcePathStr),
                            userId, versionCode, pkgFlags);
                    if (DEBUG_SETTINGS) Log.i(TAG, "Reading package " + name
                            + ": userId=" + userId + " pkg=" + packageSetting);
                    if (packageSetting == null) {
                        reportSettingsProblem(Log.ERROR,
                                "Failure adding uid " + userId
                                + " while parsing settings at "
                                + parser.getPositionDescription());
                    } else {
                        packageSetting.setTimeStamp(timeStamp, timeStampStr);
                    }
                } else if (sharedIdStr != null) {
                    userId = sharedIdStr != null
                            ? Integer.parseInt(sharedIdStr) : 0;
                    if (userId > 0) {
                        packageSetting = new PendingPackage(name.intern(), realName,
                                new File(codePathStr), new File(resourcePathStr),
                                userId, versionCode, pkgFlags);
                        packageSetting.setTimeStamp(timeStamp, timeStampStr);
                        mPendingPackages.add((PendingPackage) packageSetting);
                        if (DEBUG_SETTINGS) Log.i(TAG, "Reading package " + name
                                + ": sharedUserId=" + userId + " pkg="
                                + packageSetting);
                    } else {
                        reportSettingsProblem(Log.WARN,
                                "Error in package manager settings: package "
                                + name + " has bad sharedId " + sharedIdStr
                                + " at " + parser.getPositionDescription());
                    }
                } else {
                    reportSettingsProblem(Log.WARN,
                            "Error in package manager settings: package "
                            + name + " has bad userId " + idStr + " at "
                            + parser.getPositionDescription());
                }
            } catch (NumberFormatException e) {
                reportSettingsProblem(Log.WARN,
                        "Error in package manager settings: package "
                        + name + " has bad userId " + idStr + " at "
                        + parser.getPositionDescription());
            }
            if (packageSetting != null) {
                packageSetting.uidError = "true".equals(uidError);
                packageSetting.installerPackageName = installerPackageName;
                final String enabledStr = parser.getAttributeValue(null, "enabled");
                if (enabledStr != null) {
                    if (enabledStr.equalsIgnoreCase("true")) {
                        packageSetting.enabled = COMPONENT_ENABLED_STATE_ENABLED;
                    } else if (enabledStr.equalsIgnoreCase("false")) {
                        packageSetting.enabled = COMPONENT_ENABLED_STATE_DISABLED;
                    } else if (enabledStr.equalsIgnoreCase("default")) {
                        packageSetting.enabled = COMPONENT_ENABLED_STATE_DEFAULT;
                    } else {
                        reportSettingsProblem(Log.WARN,
                                "Error in package manager settings: package "
                                + name + " has bad enabled value: " + idStr
                                + " at " + parser.getPositionDescription());
                    }
                } else {
                    packageSetting.enabled = COMPONENT_ENABLED_STATE_DEFAULT;
                }
                final String installStatusStr = parser.getAttributeValue(null, "installStatus");
                if (installStatusStr != null) {
                    if (installStatusStr.equalsIgnoreCase("false")) {
                        packageSetting.installStatus = PKG_INSTALL_INCOMPLETE;
                    } else {
                        packageSetting.installStatus = PKG_INSTALL_COMPLETE;
                    }
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
                    if (tagName.equals("disabled-components")) {
                        readDisabledComponentsLP(packageSetting, parser);
                    } else if (tagName.equals("enabled-components")) {
                        readEnabledComponentsLP(packageSetting, parser);
                    } else if (tagName.equals("sigs")) {
                        packageSetting.signatures.readXml(parser, mPastSignatures);
                    } else if (tagName.equals("perms")) {
                        readGrantedPermissionsLP(parser,
                                packageSetting.grantedPermissions);
                        packageSetting.permissionsFixed = true;
                    } else {
                        reportSettingsProblem(Log.WARN,
                                "Unknown element under <package>: "
                                + parser.getName());
                        XmlUtils.skipCurrentTag(parser);
                    }
                }
            } else {
                XmlUtils.skipCurrentTag(parser);
            }
        }

        private void readDisabledComponentsLP(PackageSettingBase packageSetting,
                                                  XmlPullParser parser)
                throws IOException, XmlPullParserException {
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
                if (tagName.equals("item")) {
                    String name = parser.getAttributeValue(null, "name");
                    if (name != null) {
                        packageSetting.disabledComponents.add(name.intern());
                    } else {
                        reportSettingsProblem(Log.WARN,
                                "Error in package manager settings: <disabled-components> has"
                                + " no name at " + parser.getPositionDescription());
                    }
                } else {
                    reportSettingsProblem(Log.WARN,
                            "Unknown element under <disabled-components>: "
                            + parser.getName());
                }
                XmlUtils.skipCurrentTag(parser);
            }
        }

        private void readEnabledComponentsLP(PackageSettingBase packageSetting,
                                                  XmlPullParser parser)
                throws IOException, XmlPullParserException {
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
                if (tagName.equals("item")) {
                    String name = parser.getAttributeValue(null, "name");
                    if (name != null) {
                        packageSetting.enabledComponents.add(name.intern());
                    } else {
                        reportSettingsProblem(Log.WARN,
                                "Error in package manager settings: <enabled-components> has"
                                   + " no name at " + parser.getPositionDescription());
                    }
                } else {
                    reportSettingsProblem(Log.WARN,
                            "Unknown element under <enabled-components>: "
                            + parser.getName());
                }
                XmlUtils.skipCurrentTag(parser);
            }
        }

        private void readSharedUserLP(XmlPullParser parser)
                throws XmlPullParserException, IOException {
            String name = null;
            String idStr = null;
            int pkgFlags = 0;
            SharedUserSetting su = null;
            try {
                name = parser.getAttributeValue(null, "name");
                idStr = parser.getAttributeValue(null, "userId");
                int userId = idStr != null ? Integer.parseInt(idStr) : 0;
                if ("true".equals(parser.getAttributeValue(null, "system"))) {
                    pkgFlags |= ApplicationInfo.FLAG_SYSTEM;
                }
                if (name == null) {
                    reportSettingsProblem(Log.WARN,
                            "Error in package manager settings: <shared-user> has no name at "
                            + parser.getPositionDescription());
                } else if (userId == 0) {
                    reportSettingsProblem(Log.WARN,
                            "Error in package manager settings: shared-user "
                            + name + " has bad userId " + idStr + " at "
                            + parser.getPositionDescription());
                } else {
                    if ((su=addSharedUserLP(name.intern(), userId, pkgFlags)) == null) {
                        reportSettingsProblem(Log.ERROR,
                                "Occurred while parsing settings at "
                                + parser.getPositionDescription());
                    }
                }
            } catch (NumberFormatException e) {
                reportSettingsProblem(Log.WARN,
                        "Error in package manager settings: package "
                        + name + " has bad userId " + idStr + " at "
                        + parser.getPositionDescription());
            };

            if (su != null) {
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
                    if (tagName.equals("sigs")) {
                        su.signatures.readXml(parser, mPastSignatures);
                    } else if (tagName.equals("perms")) {
                        readGrantedPermissionsLP(parser, su.grantedPermissions);
                    } else {
                        reportSettingsProblem(Log.WARN,
                                "Unknown element under <shared-user>: "
                                + parser.getName());
                        XmlUtils.skipCurrentTag(parser);
                    }
                }

            } else {
                XmlUtils.skipCurrentTag(parser);
            }
        }

        private void readGrantedPermissionsLP(XmlPullParser parser,
                HashSet<String> outPerms) throws IOException, XmlPullParserException {
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
                if (tagName.equals("item")) {
                    String name = parser.getAttributeValue(null, "name");
                    if (name != null) {
                        outPerms.add(name.intern());
                    } else {
                        reportSettingsProblem(Log.WARN,
                                "Error in package manager settings: <perms> has"
                                   + " no name at " + parser.getPositionDescription());
                    }
                } else {
                    reportSettingsProblem(Log.WARN,
                            "Unknown element under <perms>: "
                            + parser.getName());
                }
                XmlUtils.skipCurrentTag(parser);
            }
        }

        private void readPreferredActivitiesLP(XmlPullParser parser)
                throws XmlPullParserException, IOException {
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
                if (tagName.equals("item")) {
                    PreferredActivity pa = new PreferredActivity(parser);
                    if (pa.mParseError == null) {
                        mPreferredActivities.addFilter(pa);
                    } else {
                        reportSettingsProblem(Log.WARN,
                                "Error in package manager settings: <preferred-activity> "
                                + pa.mParseError + " at "
                                + parser.getPositionDescription());
                    }
                } else {
                    reportSettingsProblem(Log.WARN,
                            "Unknown element under <preferred-activities>: "
                            + parser.getName());
                    XmlUtils.skipCurrentTag(parser);
                }
            }
        }

        // Returns -1 if we could not find an available UserId to assign
        private int newUserIdLP(Object obj) {
            // Let's be stupidly inefficient for now...
            final int N = mUserIds.size();
            for (int i=0; i<N; i++) {
                if (mUserIds.get(i) == null) {
                    mUserIds.set(i, obj);
                    return FIRST_APPLICATION_UID + i;
                }
            }

            // None left?
            if (N >= MAX_APPLICATION_UIDS) {
                return -1;
            }

            mUserIds.add(obj);
            return FIRST_APPLICATION_UID + N;
        }

        public PackageSetting getDisabledSystemPkg(String name) {
            synchronized(mPackages) {
                PackageSetting ps = mDisabledSysPackages.get(name);
                return ps;
            }
        }

        boolean isEnabledLP(ComponentInfo componentInfo, int flags) {
            final PackageSetting packageSettings = mPackages.get(componentInfo.packageName);
            if (Config.LOGV) {
                Log.v(TAG, "isEnabledLock - packageName = " + componentInfo.packageName
                           + " componentName = " + componentInfo.name);
                Log.v(TAG, "enabledComponents: "
                           + Arrays.toString(packageSettings.enabledComponents.toArray()));
                Log.v(TAG, "disabledComponents: "
                           + Arrays.toString(packageSettings.disabledComponents.toArray()));
            }
            if (packageSettings == null) {
                if (false) {
                    Log.w(TAG, "WAITING FOR DEBUGGER");
                    Debug.waitForDebugger();
                    Log.i(TAG, "We will crash!");
                }
            }
            return ((flags&PackageManager.GET_DISABLED_COMPONENTS) != 0)
                   || ((componentInfo.enabled
                        && ((packageSettings.enabled == COMPONENT_ENABLED_STATE_ENABLED)
                            || (componentInfo.applicationInfo.enabled
                                && packageSettings.enabled != COMPONENT_ENABLED_STATE_DISABLED))
                        && !packageSettings.disabledComponents.contains(componentInfo.name))
                       || packageSettings.enabledComponents.contains(componentInfo.name));
        }
    }

    // ------- apps on sdcard specific code -------
    static final boolean DEBUG_SD_INSTALL = false;
    final private String mSdEncryptKey = "AppsOnSD";
    final private String mSdEncryptAlg = "AES";
    private boolean mMediaMounted = false;
    private static final int MAX_CONTAINERS = 250;

    private String getEncryptKey() {
        try {
            String sdEncKey = SystemKeyStore.getInstance().retrieveKeyHexString(mSdEncryptKey);
            if (sdEncKey == null) {
                sdEncKey = SystemKeyStore.getInstance().
                        generateNewKeyHexString(128, mSdEncryptAlg, mSdEncryptKey);
                if (sdEncKey == null) {
                    Slog.e(TAG, "Failed to create encryption keys");
                    return null;
                }
            }
            return sdEncKey;
        } catch (NoSuchAlgorithmException nsae) {
            Slog.e(TAG, "Failed to create encryption keys with exception: " + nsae);
            return null;
        }
    }

   static String getTempContainerId() {
       String prefix = "smdl2tmp";
       int tmpIdx = 1;
       String list[] = PackageHelper.getSecureContainerList();
       if (list != null) {
           int idx = 0;
           int idList[] = new int[MAX_CONTAINERS];
           boolean neverFound = true;
           for (String name : list) {
               // Ignore null entries
               if (name == null) {
                   continue;
               }
               int sidx = name.indexOf(prefix);
               if (sidx == -1) {
                   // Not a temp file. just ignore
                   continue;
               }
               String subStr = name.substring(sidx + prefix.length());
               idList[idx] = -1;
               if (subStr != null) {
                   try {
                       int cid = Integer.parseInt(subStr);
                       idList[idx++] = cid;
                       neverFound = false;
                   } catch (NumberFormatException e) {
                   }
               }
           }
           if (!neverFound) {
               // Sort idList
               Arrays.sort(idList);
               for (int j = 1; j <= idList.length; j++) {
                   if (idList[j-1] != j) {
                       tmpIdx = j;
                       break;
                   }
               }
           }
       }
       return prefix + tmpIdx;
   }

   /*
    * Update media status on PackageManager.
    */
   public void updateExternalMediaStatus(final boolean mediaStatus, final boolean reportStatus) {
       if (Binder.getCallingUid() != Process.SYSTEM_UID) {
           throw new SecurityException("Media status can only be updated by the system");
       }
       synchronized (mPackages) {
           Log.i(TAG, "Updating external media status from " +
                   (mMediaMounted ? "mounted" : "unmounted") + " to " +
                   (mediaStatus ? "mounted" : "unmounted"));
           if (DEBUG_SD_INSTALL) Log.i(TAG, "updateExternalMediaStatus:: mediaStatus=" +
                   mediaStatus+", mMediaMounted=" + mMediaMounted);
           if (mediaStatus == mMediaMounted) {
               Message msg = mHandler.obtainMessage(UPDATED_MEDIA_STATUS,
                       reportStatus ? 1 : 0, -1);
               mHandler.sendMessage(msg);
               return;
           }
           mMediaMounted = mediaStatus;
       }
       // Queue up an async operation since the package installation may take a little while.
       mHandler.post(new Runnable() {
           public void run() {
               mHandler.removeCallbacks(this);
               updateExternalMediaStatusInner(mediaStatus, reportStatus);
           }
       });
   }

   /*
    * Collect information of applications on external media, map them
    * against existing containers and update information based on current
    * mount status. Please note that we always have to report status
    * if reportStatus has been set to true especially when unloading packages.
    */
   private void updateExternalMediaStatusInner(boolean mediaStatus,
           boolean reportStatus) {
       // Collection of uids
       int uidArr[] = null;
       // Collection of stale containers
       HashSet<String> removeCids = new HashSet<String>();
       // Collection of packages on external media with valid containers.
       HashMap<SdInstallArgs, String> processCids = new HashMap<SdInstallArgs, String>();
       // Get list of secure containers.
       final String list[] = PackageHelper.getSecureContainerList();
       if (list == null || list.length == 0) {
           Log.i(TAG, "No secure containers on sdcard");
       } else {
           // Process list of secure containers and categorize them
           // as active or stale based on their package internal state.
           int uidList[] = new int[list.length];
           int num = 0;
           synchronized (mPackages) {
               for (String cid : list) {
                   SdInstallArgs args = new SdInstallArgs(cid);
                   if (DEBUG_SD_INSTALL) Log.i(TAG, "Processing container " + cid);
                   String pkgName = args.getPackageName();
                   if (pkgName == null) {
                       if (DEBUG_SD_INSTALL) Log.i(TAG, "Container : " + cid + " stale");
                       removeCids.add(cid);
                       continue;
                   }
                   if (DEBUG_SD_INSTALL) Log.i(TAG, "Looking for pkg : " + pkgName);
                   PackageSetting ps = mSettings.mPackages.get(pkgName);
                   // The package status is changed only if the code path
                   // matches between settings and the container id.
                   if (ps != null && ps.codePathString != null &&
                           ps.codePathString.equals(args.getCodePath())) {
                       if (DEBUG_SD_INSTALL) Log.i(TAG, "Container : " + cid +
                               " corresponds to pkg : " + pkgName +
                               " at code path: " + ps.codePathString);
                       // We do have a valid package installed on sdcard
                       processCids.put(args, ps.codePathString);
                       int uid = ps.userId;
                       if (uid != -1) {
                           uidList[num++] = uid;
                       }
                   } else {
                       // Stale container on sdcard. Just delete
                       if (DEBUG_SD_INSTALL) Log.i(TAG, "Container : " + cid + " stale");
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
                   if (uidList[i-1] != uidList[i]) {
                       uidArr[di++] = uidList[i];
                   }
               }
           }
       }
       // Process packages with valid entries.
       if (mediaStatus) {
           if (DEBUG_SD_INSTALL) Log.i(TAG, "Loading packages");
           loadMediaPackages(processCids, uidArr, removeCids);
           startCleaningPackages();
       } else {
           if (DEBUG_SD_INSTALL) Log.i(TAG, "Unloading packages");
           unloadMediaPackages(processCids, uidArr, reportStatus);
       }
   }

   private void sendResourcesChangedBroadcast(boolean mediaStatus,
           ArrayList<String> pkgList, int uidArr[], IIntentReceiver finishedReceiver) {
       int size = pkgList.size();
       if (size > 0) {
           // Send broadcasts here
           Bundle extras = new Bundle();
           extras.putStringArray(Intent.EXTRA_CHANGED_PACKAGE_LIST,
                   pkgList.toArray(new String[size]));
           if (uidArr != null) {
               extras.putIntArray(Intent.EXTRA_CHANGED_UID_LIST, uidArr);
           }
           String action = mediaStatus ? Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE
                   : Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE;
           sendPackageBroadcast(action, null, extras, finishedReceiver);
       }
   }

   /*
    * Look at potentially valid container ids from processCids
    * If package information doesn't match the one on record
    * or package scanning fails, the cid is added to list of
    * removeCids. We currently don't delete stale containers.
    */
   private void loadMediaPackages(HashMap<SdInstallArgs, String> processCids,
           int uidArr[], HashSet<String> removeCids) {
       ArrayList<String> pkgList = new ArrayList<String>();
       Set<SdInstallArgs> keys = processCids.keySet();
       boolean doGc = false;
       for (SdInstallArgs args : keys) {
           String codePath = processCids.get(args);
           if (DEBUG_SD_INSTALL) Log.i(TAG, "Loading container : "
                   + args.cid);
           int retCode = PackageManager.INSTALL_FAILED_CONTAINER_ERROR;
           try {
               // Make sure there are no container errors first.
               if (args.doPreInstall(PackageManager.INSTALL_SUCCEEDED)
                       != PackageManager.INSTALL_SUCCEEDED) {
                   Slog.e(TAG, "Failed to mount cid : " + args.cid +
                   " when installing from sdcard");
                   continue;
               }
               // Check code path here.
               if (codePath == null || !codePath.equals(args.getCodePath())) {
                   Slog.e(TAG, "Container " + args.cid + " cachepath " + args.getCodePath()+
                           " does not match one in settings " + codePath);
                   continue;
               }
               // Parse package
               int parseFlags = PackageParser.PARSE_ON_SDCARD | mDefParseFlags;
               doGc = true;
               synchronized (mInstallLock) {
                   final PackageParser.Package pkg =  scanPackageLI(new File(codePath),
                           parseFlags, 0);
                   // Scan the package
                   if (pkg != null) {
                       synchronized (mPackages) {
                           retCode = PackageManager.INSTALL_SUCCEEDED;
                           pkgList.add(pkg.packageName);
                           // Post process args
                           args.doPostInstall(PackageManager.INSTALL_SUCCEEDED);
                       }
                   } else {
                       Slog.i(TAG, "Failed to install pkg from  " +
                               codePath + " from sdcard");
                   }
               }

           } finally {
               if (retCode != PackageManager.INSTALL_SUCCEEDED) {
                   // Don't destroy container here. Wait till gc clears things up.
                   removeCids.add(args.cid);
               }
           }
       }
       synchronized (mPackages) {
           // If the platform SDK has changed since the last time we booted,
           // we need to re-grant app permission to catch any new ones that
           // appear.  This is really a hack, and means that apps can in some
           // cases get permissions that the user didn't initially explicitly
           // allow...  it would be nice to have some better way to handle
           // this situation.
           final boolean regrantPermissions = mSettings.mExternalSdkPlatform
                   != mSdkVersion;
           if (regrantPermissions) Slog.i(TAG, "Platform changed from "
                   + mSettings.mExternalSdkPlatform + " to " + mSdkVersion
                   + "; regranting permissions for external storage");
           mSettings.mExternalSdkPlatform = mSdkVersion;
           
           // Make sure group IDs have been assigned, and any permission
           // changes in other apps are accounted for
           updatePermissionsLP(null, null, true, regrantPermissions, regrantPermissions);
           // Persist settings
           mSettings.writeLP();
       }
       // Send a broadcast to let everyone know we are done processing
       if (pkgList.size() > 0) {
           sendResourcesChangedBroadcast(true, pkgList, uidArr, null);
       }
       // Force gc to avoid any stale parser references that we might have.
       if (doGc) {
           Runtime.getRuntime().gc();
       }
       // List stale containers.
       if (removeCids != null) {
           for (String cid : removeCids) {
               Log.w(TAG, "Container " + cid + " is stale");
           }
       }
   }

   /*
    * Utility method to unload a list of specified containers
    */
   private void unloadAllContainers(Set<SdInstallArgs> cidArgs) {
       // Just unmount all valid containers.
       for (SdInstallArgs arg : cidArgs) {
           synchronized (mInstallLock) {
               arg.doPostDeleteLI(false);
           }
       }
   }

   /*
    * Unload packages mounted on external media. This involves deleting
    * package data from internal structures, sending broadcasts about
    * diabled packages, gc'ing to free up references, unmounting all
    * secure containers corresponding to packages on external media, and
    * posting a UPDATED_MEDIA_STATUS message if status has been requested.
    * Please note that we always have to post this message if status has
    * been requested no matter what.
    */
   private void unloadMediaPackages(HashMap<SdInstallArgs, String> processCids,
           int uidArr[], final boolean reportStatus) {
       if (DEBUG_SD_INSTALL) Log.i(TAG, "unloading media packages");
       ArrayList<String> pkgList = new ArrayList<String>();
       ArrayList<SdInstallArgs> failedList = new ArrayList<SdInstallArgs>();
       final Set<SdInstallArgs> keys = processCids.keySet();
       for (SdInstallArgs args : keys) {
           String cid = args.cid;
           String pkgName = args.getPackageName();
           if (DEBUG_SD_INSTALL) Log.i(TAG, "Trying to unload pkg : " + pkgName);
           // Delete package internally
           PackageRemovedInfo outInfo = new PackageRemovedInfo();
           synchronized (mInstallLock) {
               boolean res = deletePackageLI(pkgName, false,
                       PackageManager.DONT_DELETE_DATA, outInfo);
               if (res) {
                   pkgList.add(pkgName);
               } else {
                   Slog.e(TAG, "Failed to delete pkg from sdcard : " + pkgName);
                   failedList.add(args);
               }
           }
       }
       // We have to absolutely send UPDATED_MEDIA_STATUS only
       // after confirming that all the receivers processed the ordered
       // broadcast when packages get disabled, force a gc to clean things up.
       // and unload all the containers.
       if (pkgList.size() > 0) {
           sendResourcesChangedBroadcast(false, pkgList, uidArr, new IIntentReceiver.Stub() {
               public void performReceive(Intent intent, int resultCode, String data, Bundle extras,
                       boolean ordered, boolean sticky) throws RemoteException {
                   Message msg = mHandler.obtainMessage(UPDATED_MEDIA_STATUS,
                           reportStatus ? 1 : 0, 1, keys);
                   mHandler.sendMessage(msg);
               }
           });
       } else {
           Message msg = mHandler.obtainMessage(UPDATED_MEDIA_STATUS,
                   reportStatus ? 1 : 0, -1, keys);
           mHandler.sendMessage(msg);
       }
   }

   public void movePackage(final String packageName,
           final IPackageMoveObserver observer, final int flags) {
       mContext.enforceCallingOrSelfPermission(
               android.Manifest.permission.MOVE_PACKAGE, null);
       int returnCode = PackageManager.MOVE_SUCCEEDED;
       int currFlags = 0;
       int newFlags = 0;
       synchronized (mPackages) {
           PackageParser.Package pkg = mPackages.get(packageName);
           if (pkg == null) {
               returnCode =  PackageManager.MOVE_FAILED_DOESNT_EXIST;
           } else {
               // Disable moving fwd locked apps and system packages
               if (pkg.applicationInfo != null &&
                       (pkg.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                   Slog.w(TAG, "Cannot move system application");
                   returnCode = PackageManager.MOVE_FAILED_SYSTEM_PACKAGE;
               } else if (pkg.applicationInfo != null &&
                       (pkg.applicationInfo.flags & ApplicationInfo.FLAG_FORWARD_LOCK) != 0) {
                   Slog.w(TAG, "Cannot move forward locked app.");
                   returnCode = PackageManager.MOVE_FAILED_FORWARD_LOCKED;
               } else {
                   // Find install location first
                   if ((flags & PackageManager.MOVE_EXTERNAL_MEDIA) != 0 &&
                           (flags & PackageManager.MOVE_INTERNAL) != 0) {
                       Slog.w(TAG, "Ambigous flags specified for move location.");
                       returnCode = PackageManager.MOVE_FAILED_INVALID_LOCATION;
                   } else {
                       newFlags = (flags & PackageManager.MOVE_EXTERNAL_MEDIA) != 0 ?
                               PackageManager.INSTALL_EXTERNAL : PackageManager.INSTALL_INTERNAL;
                       currFlags = (pkg.applicationInfo.flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0 ?
                               PackageManager.INSTALL_EXTERNAL : PackageManager.INSTALL_INTERNAL;
                       if (newFlags == currFlags) {
                           Slog.w(TAG, "No move required. Trying to move to same location");
                           returnCode = PackageManager.MOVE_FAILED_INVALID_LOCATION;
                       }
                   }
               }
           }
           if (returnCode != PackageManager.MOVE_SUCCEEDED) {
               processPendingMove(new MoveParams(null, observer, 0, packageName), returnCode);
           } else {
               Message msg = mHandler.obtainMessage(INIT_COPY);
               InstallArgs srcArgs = createInstallArgs(currFlags, pkg.applicationInfo.sourceDir,
                       pkg.applicationInfo.publicSourceDir);
               MoveParams mp = new MoveParams(srcArgs, observer, newFlags,
                       packageName);
               msg.obj = mp;
               mHandler.sendMessage(msg);
           }
       }
   }

   private void processPendingMove(final MoveParams mp, final int currentStatus) {
       // Queue up an async operation since the package deletion may take a little while.
       mHandler.post(new Runnable() {
           public void run() {
               mHandler.removeCallbacks(this);
               int returnCode = currentStatus;
               if (currentStatus == PackageManager.MOVE_SUCCEEDED) {
                   int uidArr[] = null;
                   ArrayList<String> pkgList = null;
                   synchronized (mPackages) {
                       PackageParser.Package pkg = mPackages.get(mp.packageName);
                       if (pkg == null ) {
                           Slog.w(TAG, " Package " + mp.packageName +
                           " doesn't exist. Aborting move");
                           returnCode = PackageManager.MOVE_FAILED_DOESNT_EXIST;
                       } else if (!mp.srcArgs.getCodePath().equals(pkg.applicationInfo.sourceDir)) {
                           Slog.w(TAG, "Package " + mp.packageName + " code path changed from " +
                                   mp.srcArgs.getCodePath() + " to " + pkg.applicationInfo.sourceDir +
                           " Aborting move and returning error");
                           returnCode = PackageManager.MOVE_FAILED_INTERNAL_ERROR;
                       } else {
                           uidArr = new int[] { pkg.applicationInfo.uid };
                           pkgList = new ArrayList<String>();
                           pkgList.add(mp.packageName);
                       }
                   }
                   if (returnCode == PackageManager.MOVE_SUCCEEDED) {
                       // Send resources unavailable broadcast
                       sendResourcesChangedBroadcast(false, pkgList, uidArr, null);
                       // Update package code and resource paths
                       synchronized (mInstallLock) {
                           synchronized (mPackages) {
                               PackageParser.Package pkg = mPackages.get(mp.packageName);
                               // Recheck for package again.
                               if (pkg == null ) {
                                   Slog.w(TAG, " Package " + mp.packageName +
                                   " doesn't exist. Aborting move");
                                   returnCode = PackageManager.MOVE_FAILED_DOESNT_EXIST;
                               } else if (!mp.srcArgs.getCodePath().equals(pkg.applicationInfo.sourceDir)) {
                                   Slog.w(TAG, "Package " + mp.packageName + " code path changed from " +
                                           mp.srcArgs.getCodePath() + " to " + pkg.applicationInfo.sourceDir +
                                   " Aborting move and returning error");
                                   returnCode = PackageManager.MOVE_FAILED_INTERNAL_ERROR;
                               } else {
                                   String oldCodePath = pkg.mPath;
                                   String newCodePath = mp.targetArgs.getCodePath();
                                   String newResPath = mp.targetArgs.getResourcePath();
                                   pkg.mPath = newCodePath;
                                   // Move dex files around
                                   if (moveDexFilesLI(pkg)
                                           != PackageManager.INSTALL_SUCCEEDED) {
                                       // Moving of dex files failed. Set
                                       // error code and abort move.
                                       pkg.mPath = pkg.mScanPath;
                                       returnCode = PackageManager.MOVE_FAILED_INSUFFICIENT_STORAGE;
                                   } else {
                                       pkg.mScanPath = newCodePath;
                                       pkg.applicationInfo.sourceDir = newCodePath;
                                       pkg.applicationInfo.publicSourceDir = newResPath;
                                       PackageSetting ps = (PackageSetting) pkg.mExtras;
                                       ps.codePath = new File(pkg.applicationInfo.sourceDir);
                                       ps.codePathString = ps.codePath.getPath();
                                       ps.resourcePath = new File(pkg.applicationInfo.publicSourceDir);
                                       ps.resourcePathString = ps.resourcePath.getPath();
                                       // Set the application info flag correctly.
                                       if ((mp.flags & PackageManager.INSTALL_EXTERNAL) != 0) {
                                           pkg.applicationInfo.flags |= ApplicationInfo.FLAG_EXTERNAL_STORAGE;
                                       } else {
                                           pkg.applicationInfo.flags &= ~ApplicationInfo.FLAG_EXTERNAL_STORAGE;
                                       }
                                       ps.setFlags(pkg.applicationInfo.flags);
                                       mAppDirs.remove(oldCodePath);
                                       mAppDirs.put(newCodePath, pkg);
                                       // Persist settings
                                       mSettings.writeLP();
                                   }
                               }
                           }
                       }
                       // Send resources available broadcast
                       sendResourcesChangedBroadcast(true, pkgList, uidArr, null);
                   }
               }
               if (returnCode != PackageManager.MOVE_SUCCEEDED){
                   // Clean up failed installation
                   if (mp.targetArgs != null) {
                       mp.targetArgs.doPostInstall(PackageManager.INSTALL_FAILED_INTERNAL_ERROR);
                   }
               } else {
                   // Force a gc to clear things up.
                   Runtime.getRuntime().gc();
                   // Delete older code
                   synchronized (mInstallLock) {
                       mp.srcArgs.doPostDeleteLI(true);
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

   public boolean setInstallLocation(int loc) {
       mContext.enforceCallingOrSelfPermission(
               android.Manifest.permission.WRITE_SECURE_SETTINGS, null);
       if (getInstallLocation() == loc) {
           return true;
       }
       if (loc == PackageHelper.APP_INSTALL_AUTO ||
               loc == PackageHelper.APP_INSTALL_INTERNAL ||
               loc == PackageHelper.APP_INSTALL_EXTERNAL) {
           android.provider.Settings.System.putInt(mContext.getContentResolver(),
                   android.provider.Settings.Secure.DEFAULT_INSTALL_LOCATION, loc);
           return true;
       }
       return false;
   }

   public int getInstallLocation() {
       return android.provider.Settings.System.getInt(mContext.getContentResolver(),
               android.provider.Settings.Secure.DEFAULT_INSTALL_LOCATION, PackageHelper.APP_INSTALL_AUTO);
   }
}
