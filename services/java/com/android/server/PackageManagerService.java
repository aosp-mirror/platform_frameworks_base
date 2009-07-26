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

import com.android.internal.app.ResolverActivity;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.IntentSender.SendIntentException;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.IPackageDataObserver;
import android.content.pm.IPackageDeleteObserver;
import android.content.pm.IPackageInstallObserver;
import android.content.pm.IPackageManager;
import android.content.pm.IPackageStatsObserver;
import android.content.pm.InstrumentationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageStats;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
import static android.content.pm.PackageManager.PKG_INSTALL_COMPLETE;
import static android.content.pm.PackageManager.PKG_INSTALL_INCOMPLETE;
import android.content.pm.PackageParser;
import android.content.pm.PermissionInfo;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.Signature;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.HandlerThread;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

class PackageManagerService extends IPackageManager.Stub {
    private static final String TAG = "PackageManager";
    private static final boolean DEBUG_SETTINGS = false;
    private static final boolean DEBUG_PREFERRED = false;

    private static final boolean MULTIPLE_APPLICATION_UIDS = true;
    private static final int RADIO_UID = Process.PHONE_UID;
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

    static final int SCAN_MONITOR = 1<<0;
    static final int SCAN_NO_DEX = 1<<1;
    static final int SCAN_FORCE_DEX = 1<<2;
    static final int SCAN_UPDATE_SIGNATURE = 1<<3;
    static final int SCAN_FORWARD_LOCKED = 1<<4;
    static final int SCAN_NEW_INSTALL = 1<<5;
    
    static final int LOG_BOOT_PROGRESS_PMS_START = 3060;
    static final int LOG_BOOT_PROGRESS_PMS_SYSTEM_SCAN_START = 3070;
    static final int LOG_BOOT_PROGRESS_PMS_DATA_SCAN_START = 3080;
    static final int LOG_BOOT_PROGRESS_PMS_SCAN_END = 3090;
    static final int LOG_BOOT_PROGRESS_PMS_READY = 3100;

    final HandlerThread mHandlerThread = new HandlerThread("PackageManager",
            Process.THREAD_PRIORITY_BACKGROUND);
    final Handler mHandler;

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
    boolean mReportedUidError;

    // Group-ids that are given to all packages as read from etc/permissions/*.xml.
    int[] mGlobalGids;

    // These are the built-in uid -> permission mappings that were read from the
    // etc/permissions.xml file.
    final SparseArray<HashSet<String>> mSystemPermissions =
            new SparseArray<HashSet<String>>();
    
    // These are the built-in shared libraries that were read from the
    // etc/permissions.xml file.
    final HashMap<String, String> mSharedLibraries = new HashMap<String, String>();
    
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

    boolean mSystemReady;
    boolean mSafeMode;
    boolean mHasSystemUidErrors;

    ApplicationInfo mAndroidApplication;
    final ActivityInfo mResolveActivity = new ActivityInfo();
    final ResolveInfo mResolveInfo = new ResolveInfo();
    ComponentName mResolveComponentName;
    PackageParser.Package mPlatformPackage;
    private boolean mCompatibilityModeEnabled = true;

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
        EventLog.writeEvent(LOG_BOOT_PROGRESS_PMS_START,
                SystemClock.uptimeMillis());
        
        if (mSdkVersion <= 0) {
            Log.w(TAG, "**** ro.build.version.sdk not set!");
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

        String separateProcesses = SystemProperties.get("debug.separate_processes");
        if (separateProcesses != null && separateProcesses.length() > 0) {
            if ("*".equals(separateProcesses)) {
                mDefParseFlags = PackageParser.PARSE_IGNORE_PROCESSES;
                mSeparateProcesses = null;
                Log.w(TAG, "Running with debug.separate_processes: * (ALL)");
            } else {
                mDefParseFlags = 0;
                mSeparateProcesses = separateProcesses.split(",");
                Log.w(TAG, "Running with debug.separate_processes: "
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
            mHandler = new Handler(mHandlerThread.getLooper());
            
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
            
            EventLog.writeEvent(LOG_BOOT_PROGRESS_PMS_SYSTEM_SCAN_START,
                    startTime);
            
            int scanMode = SCAN_MONITOR;
            if (mNoDexOpt) {
                Log.w(TAG, "Running ENG build: no pre-dexopt!");
                scanMode |= SCAN_NO_DEX; 
            }
            
            final HashSet<String> libFiles = new HashSet<String>();
            
            mFrameworkDir = new File(Environment.getRootDirectory(), "framework");
            
            if (mInstaller != null) {
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
                            }
                        } catch (FileNotFoundException e) {
                            Log.w(TAG, "Boot class path not found: " + paths[i]);
                        } catch (IOException e) {
                            Log.w(TAG, "Exception reading boot class path: " + paths[i], e);
                        }
                    }
                } else {
                    Log.w(TAG, "No BOOTCLASSPATH found!");
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
                            }
                        } catch (FileNotFoundException e) {
                            Log.w(TAG, "Library not found: " + lib);
                        } catch (IOException e) {
                            Log.w(TAG, "Exception reading library: " + lib, e);
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
                if (frameworkFiles != null && mInstaller != null) {
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
                            }
                        } catch (FileNotFoundException e) {
                            Log.w(TAG, "Jar not found: " + path);
                        } catch (IOException e) {
                            Log.w(TAG, "Exception reading jar: " + path, e);
                        }
                    }
                }
            }
            
            mFrameworkInstallObserver = new AppDirObserver(
                mFrameworkDir.getPath(), OBSERVER_EVENTS, true);
            mFrameworkInstallObserver.startWatching();
            scanDirLI(mFrameworkDir, PackageParser.PARSE_IS_SYSTEM,
                    scanMode | SCAN_NO_DEX);
            mSystemAppDir = new File(Environment.getRootDirectory(), "app");
            mSystemInstallObserver = new AppDirObserver(
                mSystemAppDir.getPath(), OBSERVER_EVENTS, true);
            mSystemInstallObserver.startWatching();
            scanDirLI(mSystemAppDir, PackageParser.PARSE_IS_SYSTEM, scanMode);
            mAppInstallDir = new File(dataDir, "app");
            if (mInstaller == null) {
                // Make sure these dirs exist, when we are running in
                // the simulator.
                mAppInstallDir.mkdirs(); // scanDirLI() assumes this dir exists
            }
            //look for any incomplete package installations
            ArrayList<String> deletePkgsList = mSettings.getListOfIncompleteInstallPackages();
            //clean up list
            for(int i = 0; i < deletePkgsList.size(); i++) {
                //clean up here
                cleanupInstallFailedPackage(deletePkgsList.get(i));
            }
            //delete tmp files
            deleteTempPackageFiles();
            
            EventLog.writeEvent(LOG_BOOT_PROGRESS_PMS_DATA_SCAN_START,
                    SystemClock.uptimeMillis());
            mAppInstallObserver = new AppDirObserver(
                mAppInstallDir.getPath(), OBSERVER_EVENTS, false);
            mAppInstallObserver.startWatching();
            scanDirLI(mAppInstallDir, 0, scanMode);

            mDrmAppInstallObserver = new AppDirObserver(
                mDrmAppPrivateInstallDir.getPath(), OBSERVER_EVENTS, false);
            mDrmAppInstallObserver.startWatching();
            scanDirLI(mDrmAppPrivateInstallDir, 0, scanMode);

            EventLog.writeEvent(LOG_BOOT_PROGRESS_PMS_SCAN_END,
                    SystemClock.uptimeMillis());
            Log.i(TAG, "Time to scan packages: "
                    + ((SystemClock.uptimeMillis()-startTime)/1000f)
                    + " seconds");

            updatePermissionsLP();

            mSettings.writeLP();

            EventLog.writeEvent(LOG_BOOT_PROGRESS_PMS_READY,
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
                Log.e(TAG, "Package Manager Crash", e);
            }
            throw e;
        }
    }

    void cleanupInstallFailedPackage(String packageName) {
        if (mInstaller != null) {
            int retCode = mInstaller.remove(packageName);
            if (retCode < 0) {
                Log.w(TAG, "Couldn't remove app data directory for package: "
                           + packageName + ", retcode=" + retCode);
            }
        } else {
            //for emulator
            PackageParser.Package pkg = mPackages.get(packageName);
            File dataDir = new File(pkg.applicationInfo.dataDir);
            dataDir.delete();
        }
        mSettings.removePackageLP(packageName);
    }

    void readPermissions() {
        // Read permissions from .../etc/permission directory.
        File libraryDir = new File(Environment.getRootDirectory(), "etc/permissions");
        if (!libraryDir.exists() || !libraryDir.isDirectory()) {
            Log.w(TAG, "No directory " + libraryDir + ", skipping");
            return;
        }
        if (!libraryDir.canRead()) {
            Log.w(TAG, "Directory " + libraryDir + " cannot be read");
            return;
        }

        // Iterate over the files in the directory and scan .xml files
        for (File f : libraryDir.listFiles()) {
            // We'll read platform.xml last
            if (f.getPath().endsWith("etc/permissions/platform.xml")) {
                continue;
            }
            
            if (!f.getPath().endsWith(".xml")) {
                Log.i(TAG, "Non-xml file " + f + " in " + libraryDir + " directory, ignoring");
                continue;
            }
            if (!f.canRead()) {
                Log.w(TAG, "Permissions library file " + f + " cannot be read");
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
            Log.w(TAG, "Couldn't find or open permissions file " + permFile);
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
                        Log.w(TAG, "<group> without gid at "
                                + parser.getPositionDescription());
                    }

                    XmlUtils.skipCurrentTag(parser);
                    continue;
                } else if ("permission".equals(name)) {
                    String perm = parser.getAttributeValue(null, "name");
                    if (perm == null) {
                        Log.w(TAG, "<permission> without name at "
                                + parser.getPositionDescription());
                        XmlUtils.skipCurrentTag(parser);
                        continue;
                    }
                    perm = perm.intern();
                    readPermission(parser, perm);
                    
                } else if ("assign-permission".equals(name)) {
                    String perm = parser.getAttributeValue(null, "name");
                    if (perm == null) {
                        Log.w(TAG, "<assign-permission> without name at "
                                + parser.getPositionDescription());
                        XmlUtils.skipCurrentTag(parser);
                        continue;
                    }
                    String uidStr = parser.getAttributeValue(null, "uid");
                    if (uidStr == null) {
                        Log.w(TAG, "<assign-permission> without uid at "
                                + parser.getPositionDescription());
                        XmlUtils.skipCurrentTag(parser);
                        continue;
                    }
                    int uid = Process.getUidForName(uidStr);
                    if (uid < 0) {
                        Log.w(TAG, "<assign-permission> with unknown uid \""
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
                        Log.w(TAG, "<library> without name at "
                                + parser.getPositionDescription());
                    } else if (lfile == null) {
                        Log.w(TAG, "<library> without file at "
                                + parser.getPositionDescription());
                    } else {
                        Log.i(TAG, "Got library " + lname + " in " + lfile);
                        this.mSharedLibraries.put(lname, lfile);
                    }
                    XmlUtils.skipCurrentTag(parser);
                    continue;
                    
                } else {
                    XmlUtils.skipCurrentTag(parser);
                    continue;
                }

            }
        } catch (XmlPullParserException e) {
            Log.w(TAG, "Got execption parsing permissions.", e);
        } catch (IOException e) {
            Log.w(TAG, "Got execption parsing permissions.", e);
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
                    Log.w(TAG, "<group> without gid at "
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

    PackageInfo generatePackageInfo(PackageParser.Package p, int flags) {
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
                TAG, "getApplicationInfo " + packageName
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
                TAG, "getApplicationInfo " + packageName
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

    public PermissionInfo getPermissionInfo(String name, int flags) {
        synchronized (mPackages) {
            final BasePermission p = mSettings.mPermissions.get(name);
            if (p != null && p.perm != null) {
                return PackageParser.generatePermissionInfo(p.perm, flags);
            }
            return null;
        }
    }

    public List<PermissionInfo> queryPermissionsByGroup(String group, int flags) {
        synchronized (mPackages) {
            ArrayList<PermissionInfo> out = new ArrayList<PermissionInfo>(10);
            for (BasePermission p : mSettings.mPermissions.values()) {
                if (group == null) {
                    if (p.perm.info.group == null) {
                        out.add(PackageParser.generatePermissionInfo(p.perm, flags));
                    }
                } else {
                    if (group.equals(p.perm.info.group)) {
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
                ApplicationInfo appInfo = PackageParser.generateApplicationInfo(p, flags);
                if (!mCompatibilityModeEnabled) {
                    appInfo.disableCompatibilityMode();
                }
                return appInfo;
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
                        Log.w(TAG, "Couldn't clear application caches");
                    }
                } //end if mInstaller
                if (observer != null) {
                    try {
                        observer.onRemoveCompleted(null, (retCode >= 0));
                    } catch (RemoteException e) {
                        Log.w(TAG, "RemoveException when invoking call back");
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
                        Log.w(TAG, "Couldn't clear application caches");
                    }
                }
                if(pi != null) {
                    try {
                        // Callback via pending intent
                        int code = (retCode >= 0) ? 1 : 0;
                        pi.sendIntent(null, code, null,
                                null, null);
                    } catch (SendIntentException e1) {
                        Log.i(TAG, "Failed to send pending intent");
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
        }
        int size = libSet.size();
        if (size > 0) {
            String[] libs = new String[size];
            libSet.toArray(libs);
            return libs;
        }
        return null;
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
                if (obj instanceof SharedUserSetting) {
                    SharedUserSetting sus = (SharedUserSetting)obj;
                    if (sus.grantedPermissions.contains(permName)) {
                        return PackageManager.PERMISSION_GRANTED;
                    }
                } else if (obj instanceof PackageSetting) {
                    PackageSetting ps = (PackageSetting)obj;
                    if (ps.grantedPermissions.contains(permName)) {
                        return PackageManager.PERMISSION_GRANTED;
                    }
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

    public boolean addPermission(PermissionInfo info) {
        synchronized (mPackages) {
            if (info.labelRes == 0 && info.nonLocalizedLabel == null) {
                throw new SecurityException("Label must be specified in permission");
            }
            BasePermission tree = checkPermissionTreeLP(info.name);
            BasePermission bp = mSettings.mPermissions.get(info.name);
            boolean added = bp == null;
            if (added) {
                bp = new BasePermission(info.name, tree.sourcePackage,
                        BasePermission.TYPE_DYNAMIC);
            } else if (bp.type != BasePermission.TYPE_DYNAMIC) {
                throw new SecurityException(
                        "Not allowed to modify non-dynamic permission "
                        + info.name);
            }
            bp.perm = new PackageParser.Permission(tree.perm.owner,
                    new PermissionInfo(info));
            bp.perm.info.packageName = tree.perm.info.packageName;
            bp.uid = tree.uid;
            if (added) {
                mSettings.mPermissions.put(info.name, bp);
            }
            mSettings.writeLP();
            return added;
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

    public int checkSignatures(String pkg1, String pkg2) {
        synchronized (mPackages) {
            PackageParser.Package p1 = mPackages.get(pkg1);
            PackageParser.Package p2 = mPackages.get(pkg2);
            if (p1 == null || p1.mExtras == null
                    || p2 == null || p2.mExtras == null) {
                return PackageManager.SIGNATURE_UNKNOWN_PACKAGE;
            }
            return checkSignaturesLP(p1, p2);
        }
    }

    int checkSignaturesLP(PackageParser.Package p1, PackageParser.Package p2) {
        if (p1.mSignatures == null) {
            return p2.mSignatures == null
                    ? PackageManager.SIGNATURE_NEITHER_SIGNED
                    : PackageManager.SIGNATURE_FIRST_NOT_SIGNED;
        }
        if (p2.mSignatures == null) {
            return PackageManager.SIGNATURE_SECOND_NOT_SIGNED;
        }
        final int N1 = p1.mSignatures.length;
        final int N2 = p2.mSignatures.length;
        for (int i=0; i<N1; i++) {
            boolean match = false;
            for (int j=0; j<N2; j++) {
                if (p1.mSignatures[i].equals(p2.mSignatures[j])) {
                    match = true;
                    break;
                }
            }
            if (!match) {
                return PackageManager.SIGNATURE_NO_MATCH;
            }
        }
        return PackageManager.SIGNATURE_MATCH;
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
                                Log.i(TAG, "Result set changed, dropping preferred activity for "
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
            PackageParser.Package pkg = scanPackageLI(file, file, file, 
                    flags|PackageParser.PARSE_MUST_BE_APK, scanMode);
        }
    }

    private static void reportSettingsProblem(int priority, String msg) {
        try {
            File dataDir = Environment.getDataDirectory();
            File systemDir = new File(dataDir, "system");
            File fname = new File(systemDir, "uiderrors.txt");
            FileOutputStream out = new FileOutputStream(fname, true);
            PrintWriter pw = new PrintWriter(out);
            pw.println(msg);
            pw.close();
            FileUtils.setPermissions(
                    fname.toString(),
                    FileUtils.S_IRWXU|FileUtils.S_IRWXG|FileUtils.S_IROTH,
                    -1, -1);
        } catch (java.io.IOException e) {
        }
        Log.println(priority, TAG, msg);
    }

    private boolean collectCertificatesLI(PackageParser pp, PackageSetting ps,
            PackageParser.Package pkg, File srcFile, int parseFlags) {
        if (GET_CERTIFICATES) {
            if (ps == null || !ps.codePath.equals(srcFile)
                    || ps.getTimeStamp() != srcFile.lastModified()) {
                Log.i(TAG, srcFile.toString() + " changed; collecting certs");
                if (!pp.collectCertificates(pkg, parseFlags)) {
                    mLastScanError = pp.getParseError();
                    return false;
                }
            }
        }
        return true;
    }
    
    /*
     *  Scan a package and return the newly parsed package.
     *  Returns null in case of errors and the error code is stored in mLastScanError
     */
    private PackageParser.Package scanPackageLI(File scanFile,
            File destCodeFile, File destResourceFile, int parseFlags,
            int scanMode) {
        mLastScanError = PackageManager.INSTALL_SUCCEEDED;
        parseFlags |= mDefParseFlags;
        PackageParser pp = new PackageParser(scanFile.getPath());
        pp.setSeparateProcesses(mSeparateProcesses);
        pp.setSdkVersion(mSdkVersion, mSdkCodename);
        final PackageParser.Package pkg = pp.parsePackage(scanFile,
                destCodeFile.getAbsolutePath(), mMetrics, parseFlags);
        if (pkg == null) {
            mLastScanError = pp.getParseError();
            return null;
        }
        PackageSetting ps;
        PackageSetting updatedPkg;
        synchronized (mPackages) {
            ps = mSettings.peekPackageLP(pkg.packageName);
            updatedPkg = mSettings.mDisabledSysPackages.get(pkg.packageName);
        }
        if (updatedPkg != null) {
            // An updated system app will not have the PARSE_IS_SYSTEM flag set initially
            parseFlags |= PackageParser.PARSE_IS_SYSTEM;
        }
        if ((parseFlags&PackageParser.PARSE_IS_SYSTEM) != 0) {
            // Check for updated system applications here
            if (updatedPkg != null) {
                if ((ps != null) && (!ps.codePath.getPath().equals(scanFile.getPath()))) {
                    if (pkg.mVersionCode <= ps.versionCode) {
                     // The system package has been updated and the code path does not match
                        // Ignore entry. Just return
                        Log.w(TAG, "Package:" + pkg.packageName +
                                " has been updated. Ignoring the one from path:"+scanFile);
                        mLastScanError = PackageManager.INSTALL_FAILED_DUPLICATE_PACKAGE;
                        return null;
                    } else {
                        // Delete the older apk pointed to by ps
                        deletePackageResourcesLI(ps.name, ps.codePathString, ps.resourcePathString);
                        mSettings.enableSystemPackageLP(ps.name);
                    }
                }
            }
        }
        if (!collectCertificatesLI(pp, ps, pkg, scanFile, parseFlags)) {
            Log.i(TAG, "Failed verifying certificates for package:" + pkg.packageName);
            return null;
        }
        // The apk is forward locked (not public) if its code and resources
        // are kept in different files.
        if (ps != null && !ps.codePath.equals(ps.resourcePath)) {
            scanMode |= SCAN_FORWARD_LOCKED;
        }
        // Note that we invoke the following method only if we are about to unpack an application
        return scanPackageLI(scanFile, destCodeFile, destResourceFile,
                pkg, parseFlags, scanMode | SCAN_UPDATE_SIGNATURE);
    }

    private static String fixProcessName(String defProcessName,
            String processName, int uid) {
        if (processName == null) {
            return defProcessName;
        }
        return processName;
    }

    private boolean verifySignaturesLP(PackageSetting pkgSetting, 
            PackageParser.Package pkg, int parseFlags, boolean updateSignature) {
        if (pkg.mSignatures != null) {
            if (!pkgSetting.signatures.updateSignatures(pkg.mSignatures,
                    updateSignature)) {
                Log.e(TAG, "Package " + pkg.packageName
                        + " signatures do not match the previously installed version; ignoring!");
                mLastScanError = PackageManager.INSTALL_FAILED_UPDATE_INCOMPATIBLE;
                return false;
            }

            if (pkgSetting.sharedUser != null) {
                if (!pkgSetting.sharedUser.signatures.mergeSignatures(
                        pkg.mSignatures, updateSignature)) {
                    Log.e(TAG, "Package " + pkg.packageName
                            + " has no signatures that match those in shared user "
                            + pkgSetting.sharedUser.name + "; ignoring!");
                    mLastScanError = PackageManager.INSTALL_FAILED_SHARED_USER_INCOMPATIBLE;
                    return false;
                }
            }
        } else {
            pkg.mSignatures = pkgSetting.signatures.mSignatures;
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
                            !pkg.mForwardLocked);
                    pkg.mDidDexOpt = true;
                    performed = true;
                }
            } catch (FileNotFoundException e) {
                Log.w(TAG, "Apk not found for dexopt: " + path);
                ret = -1;
            } catch (IOException e) {
                Log.w(TAG, "Exception reading apk: " + path, e);
                ret = -1;
            }
            if (ret < 0) {
                //error from installer
                return DEX_OPT_FAILED;
            }
        }
        
        return performed ? DEX_OPT_PERFORMED : DEX_OPT_SKIPPED;
    }
    
    private PackageParser.Package scanPackageLI(
        File scanFile, File destCodeFile, File destResourceFile,
        PackageParser.Package pkg, int parseFlags, int scanMode) {

        mScanningPath = scanFile;
        if (pkg == null) {
            mLastScanError = PackageManager.INSTALL_PARSE_FAILED_BAD_PACKAGE_NAME;
            return null;
        }

        final String pkgName = pkg.applicationInfo.packageName;
        if ((parseFlags&PackageParser.PARSE_IS_SYSTEM) != 0) {
            pkg.applicationInfo.flags |= ApplicationInfo.FLAG_SYSTEM;
        }

        if (pkgName.equals("android")) {
            synchronized (mPackages) {
                if (mAndroidApplication != null) {
                    Log.w(TAG, "*************************************************");
                    Log.w(TAG, "Core android package being redefined.  Skipping.");
                    Log.w(TAG, " file=" + mScanningPath);
                    Log.w(TAG, "*************************************************");
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
                TAG, "Scanning package " + pkgName);
        if (mPackages.containsKey(pkgName) || mSharedLibraries.containsKey(pkgName)) {
            Log.w(TAG, "*************************************************");
            Log.w(TAG, "Application package " + pkgName
                    + " already installed.  Skipping duplicate.");
            Log.w(TAG, "*************************************************");
            mLastScanError = PackageManager.INSTALL_FAILED_DUPLICATE_PACKAGE;
            return null;
        }

        SharedUserSetting suid = null;
        PackageSetting pkgSetting = null;
        
        boolean removeExisting = false;
        
        synchronized (mPackages) {
            // Check all shared libraries and map to their actual file path.
            if (pkg.usesLibraryFiles != null) {
                for (int i=0; i<pkg.usesLibraryFiles.length; i++) {
                    String file = mSharedLibraries.get(pkg.usesLibraryFiles[i]);
                    if (file == null) {
                        Log.e(TAG, "Package " + pkg.packageName
                                + " requires unavailable shared library "
                                + pkg.usesLibraryFiles[i] + "; ignoring!");
                        mLastScanError = PackageManager.INSTALL_FAILED_MISSING_SHARED_LIBRARY;
                        return null;
                    }
                    pkg.usesLibraryFiles[i] = file;
                }
            }
            
            if (pkg.mSharedUserId != null) {
                suid = mSettings.getSharedUserLP(pkg.mSharedUserId,
                        pkg.applicationInfo.flags, true);
                if (suid == null) {
                    Log.w(TAG, "Creating application package " + pkgName
                            + " for shared user failed");
                    mLastScanError = PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE;
                    return null;
                }
                if ((parseFlags&PackageParser.PARSE_CHATTY) != 0 && Config.LOGD) {
                    Log.d(TAG, "Shared UserID " + pkg.mSharedUserId + " (uid="
                            + suid.userId + "): packages=" + suid.packages);
                }
            }
    
            // Just create the setting, don't add it yet
            pkgSetting = mSettings.getPackageLP(pkg, suid, destCodeFile,
                            destResourceFile, pkg.applicationInfo.flags, true, false);
            if (pkgSetting == null) {
                Log.w(TAG, "Creating application package " + pkgName + " failed");
                mLastScanError = PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE;
                return null;
            }
            if(mSettings.mDisabledSysPackages.get(pkg.packageName) != null) {
                pkg.applicationInfo.flags |= ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
            }
        
            pkg.applicationInfo.uid = pkgSetting.userId;
            pkg.mExtras = pkgSetting;
    
            if (!verifySignaturesLP(pkgSetting, pkg, parseFlags, 
                    (scanMode&SCAN_UPDATE_SIGNATURE) != 0)) {
                if ((parseFlags&PackageParser.PARSE_IS_SYSTEM) == 0) {
                    mLastScanError = PackageManager.INSTALL_FAILED_UPDATE_INCOMPATIBLE;
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
                    if (!pkgSetting.sharedUser.signatures.mergeSignatures(
                            pkg.mSignatures, false)) {
                        mLastScanError = PackageManager.INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES;
                        return null;
                    }
                }
                removeExisting = true;
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
                    String names[] = p.info.authority.split(";");
                    for (int j = 0; j < names.length; j++) {
                        if (mProviders.containsKey(names[j])) {
                            PackageParser.Provider other = mProviders.get(names[j]);
                            Log.w(TAG, "Can't install because provider name " + names[j] +
                                    " (in package " + pkg.applicationInfo.packageName +
                                    ") is already used by "
                                    + ((other != null && other.component != null)
                                            ? other.component.getPackageName() : "?"));
                            mLastScanError = PackageManager.INSTALL_FAILED_CONFLICTING_PROVIDER;
                            return null;
                        }
                    }
                }
            }
        }

        if (removeExisting) {
            if (mInstaller != null) {
                int ret = mInstaller.remove(pkgName);
                if (ret != 0) {
                    String msg = "System package " + pkg.packageName
                            + " could not have data directory erased after signature change.";
                    reportSettingsProblem(Log.WARN, msg);
                    mLastScanError = PackageManager.INSTALL_FAILED_REPLACE_COULDNT_DELETE;
                    return null;
                }
            }
            Log.w(TAG, "System package " + pkg.packageName
                    + " signature changed: existing data removed.");
            mLastScanError = PackageManager.INSTALL_SUCCEEDED;
        }
        
        long scanFileTime = scanFile.lastModified();
        final boolean forceDex = (scanMode&SCAN_FORCE_DEX) != 0;
        final boolean scanFileNewer = forceDex || scanFileTime != pkgSetting.getTimeStamp();
        pkg.applicationInfo.processName = fixProcessName(
                pkg.applicationInfo.packageName,
                pkg.applicationInfo.processName,
                pkg.applicationInfo.uid);
        pkg.applicationInfo.publicSourceDir = pkgSetting.resourcePathString;

        File dataPath;
        if (mPlatformPackage == pkg) {
            // The system package is special.
            dataPath = new File (Environment.getDataDirectory(), "system");
            pkg.applicationInfo.dataDir = dataPath.getPath();
        } else {
            // This is a normal package, need to make its data directory.
            dataPath = new File(mAppDataDir, pkgName);
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
                            if(ret >= 0) {
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
                            if (!mReportedUidError) {
                                mReportedUidError = true;
                                msg = msg + "; read messages:\n"
                                        + mSettings.getReadMessagesLP();
                            }
                            reportSettingsProblem(Log.ERROR, msg);
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
                    Log.w(TAG, "Unable to create data directory: " + dataPath);
                    pkg.applicationInfo.dataDir = null;
                }
            }
        }

        // Perform shared library installation and dex validation and
        // optimization, if this is not a system app.
        if (mInstaller != null) {
            String path = scanFile.getPath();
            if (scanFileNewer) {
                Log.i(TAG, path + " changed; unpacking");
                int err = cachePackageSharedLibsLI(pkg, dataPath, scanFile);
                if (err != PackageManager.INSTALL_SUCCEEDED) {
                    mLastScanError = err;
                    return null;
                }
            }

            pkg.mForwardLocked = (scanMode&SCAN_FORWARD_LOCKED) != 0;
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

        if ((scanMode&SCAN_MONITOR) != 0) {
            pkg.mPath = destCodeFile.getAbsolutePath();
            mAppDirs.put(pkg.mPath, pkg);
        }

        synchronized (mPackages) {
            // We don't expect installation to fail beyond this point
            // Add the new setting to mSettings
            mSettings.insertPackageSettingLP(pkgSetting, pkg.packageName, suid);
            // Add the new setting to mPackages
            mPackages.put(pkg.applicationInfo.packageName, pkg);          
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
                        Log.w(TAG, "Skipping provider name " + names[j] +
                              " (in package " + pkg.applicationInfo.packageName +
                              "): name already used by "
                              + ((other != null && other.component != null)
                                      ? other.component.getPackageName() : "?"));
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
                    Log.w(TAG, "Permission group " + pg.info.name + " from package "
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
                                Log.w(TAG, "Permission " + p.info.name + " from package "
                                        + p.info.packageName + " ignored: base tree "
                                        + tree.name + " is from package "
                                        + tree.sourcePackage);
                            }
                        } else {
                            Log.w(TAG, "Permission " + p.info.name + " from package "
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
                } else {
                    Log.w(TAG, "Permission " + p.info.name + " from package "
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
                mInstrumentation.put(a.component, a);
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
    
            pkgSetting.setTimeStamp(scanFileTime);
        }
        
        return pkg;
    }

    private int cachePackageSharedLibsLI(PackageParser.Package pkg,
            File dataPath, File scanFile) {
        File sharedLibraryDir = new File(dataPath.getPath() + "/lib");
        final String sharedLibraryABI = Build.CPU_ABI;
        final String apkLibraryDirectory = "lib/" + sharedLibraryABI + "/";
        final String apkSharedLibraryPrefix = apkLibraryDirectory + "lib";
        final String sharedLibrarySuffix = ".so";
        boolean hasNativeCode = false;
        boolean installedNativeCode = false;
        try {
            ZipFile zipFile = new ZipFile(scanFile);
            Enumeration<ZipEntry> entries =
                (Enumeration<ZipEntry>) zipFile.entries();

            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    if (!hasNativeCode && entry.getName().startsWith("lib")) {
                        hasNativeCode = true;
                    }
                    continue;
                }
                String entryName = entry.getName();
                if (entryName.startsWith("lib/")) {
                    hasNativeCode = true;
                }
                if (! (entryName.startsWith(apkSharedLibraryPrefix)
                        && entryName.endsWith(sharedLibrarySuffix))) {
                    continue;
                }
                String libFileName = entryName.substring(
                        apkLibraryDirectory.length());
                if (libFileName.contains("/")
                        || (!FileUtils.isFilenameSafe(new File(libFileName)))) {
                    continue;
                }
                
                installedNativeCode = true;
                
                String sharedLibraryFilePath = sharedLibraryDir.getPath() +
                    File.separator + libFileName;
                File sharedLibraryFile = new File(sharedLibraryFilePath);
                if (! sharedLibraryFile.exists() ||
                    sharedLibraryFile.length() != entry.getSize() ||
                    sharedLibraryFile.lastModified() != entry.getTime()) {
                    if (Config.LOGD) {
                        Log.d(TAG, "Caching shared lib " + entry.getName());
                    }
                    if (mInstaller == null) {
                        sharedLibraryDir.mkdir();
                    }
                    cacheSharedLibLI(pkg, zipFile, entry, sharedLibraryDir,
                            sharedLibraryFile);
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to cache package shared libs", e);
            return PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE;
        }
        
        if (hasNativeCode && !installedNativeCode) {
            Log.w(TAG, "Install failed: .apk has native code but none for arch "
                    + Build.CPU_ABI);
            return PackageManager.INSTALL_FAILED_CPU_ABI_INCOMPATIBLE;
        }
        
        return PackageManager.INSTALL_SUCCEEDED;
    }

    private void cacheSharedLibLI(PackageParser.Package pkg,
            ZipFile zipFile, ZipEntry entry,
            File sharedLibraryDir,
            File sharedLibraryFile) throws IOException {
        InputStream inputStream = zipFile.getInputStream(entry);
        try {
            File tempFile = File.createTempFile("tmp", "tmp", sharedLibraryDir);
            String tempFilePath = tempFile.getPath();
            // XXX package manager can't change owner, so the lib files for
            // now need to be left as world readable and owned by the system.
            if (! FileUtils.copyToFile(inputStream, tempFile) ||
                ! tempFile.setLastModified(entry.getTime()) ||
                FileUtils.setPermissions(tempFilePath,
                        FileUtils.S_IRUSR|FileUtils.S_IWUSR|FileUtils.S_IRGRP
                        |FileUtils.S_IROTH, -1, -1) != 0 ||
                ! tempFile.renameTo(sharedLibraryFile)) {
                // Failed to properly write file.
                tempFile.delete();
                throw new IOException("Couldn't create cached shared lib "
                        + sharedLibraryFile + " in " + sharedLibraryDir);
            }
        } finally {
            inputStream.close();
        }
    }

    void removePackageLI(PackageParser.Package pkg, boolean chatty) {
        if (chatty && Config.LOGD) Log.d(
            TAG, "Removing package " + pkg.applicationInfo.packageName );

        synchronized (mPackages) {
            if (pkg.mPreferredOrder > 0) {
                mSettings.mPreferredPackages.remove(pkg);
                pkg.mPreferredOrder = 0;
                updatePreferredIndicesLP();
            }
    
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
                    if (bp.type != BasePermission.TYPE_BUILTIN) {
                        if (tree) {
                            mSettings.mPermissionTrees.remove(p.info.name);
                        } else {
                            mSettings.mPermissions.remove(p.info.name);
                        }
                    } else {
                        bp.perm = null;
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
            }
            if (r != null) {
                if (Config.LOGD) Log.d(TAG, "  Permissions: " + r);
            }
    
            N = pkg.instrumentation.size();
            r = null;
            for (i=0; i<N; i++) {
                PackageParser.Instrumentation a = pkg.instrumentation.get(i);
                mInstrumentation.remove(a.component);
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

    private void updatePermissionsLP() {
        // Make sure there are no dangling permission trees.
        Iterator<BasePermission> it = mSettings.mPermissionTrees
                .values().iterator();
        while (it.hasNext()) {
            BasePermission bp = it.next();
            if (bp.perm == null) {
                Log.w(TAG, "Removing dangling permission tree: " + bp.name
                        + " from package " + bp.sourcePackage);
                it.remove();
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
                if (bp.perm == null && bp.pendingInfo != null) {
                    BasePermission tree = findPermissionTreeLP(bp.name);
                    if (tree != null) {
                        bp.perm = new PackageParser.Permission(tree.perm.owner,
                                new PermissionInfo(bp.pendingInfo));
                        bp.perm.info.packageName = tree.perm.info.packageName;
                        bp.perm.info.name = bp.name;
                        bp.uid = tree.uid;
                    }
                }
            }
            if (bp.perm == null) {
                Log.w(TAG, "Removing dangling permission: " + bp.name
                        + " from package " + bp.sourcePackage);
                it.remove();
            }
        }

        // Now update the permissions for all packages, in particular
        // replace the granted permissions of the system packages.
        for (PackageParser.Package pkg : mPackages.values()) {
            grantPermissionsLP(pkg, false);
        }
    }
    
    private void grantPermissionsLP(PackageParser.Package pkg, boolean replace) {
        final PackageSetting ps = (PackageSetting)pkg.mExtras;
        if (ps == null) {
            return;
        }
        final GrantedPermissions gp = ps.sharedUser != null ? ps.sharedUser : ps;
        boolean addedPermission = false;
        
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
            PackageParser.Permission p = bp != null ? bp.perm : null;
            if (false) {
                if (gp != ps) {
                    Log.i(TAG, "Package " + pkg.packageName + " checking " + name
                            + ": " + p);
                }
            }
            if (p != null) {
                final String perm = p.info.name;
                boolean allowed;
                if (p.info.protectionLevel == PermissionInfo.PROTECTION_NORMAL
                        || p.info.protectionLevel == PermissionInfo.PROTECTION_DANGEROUS) {
                    allowed = true;
                } else if (p.info.protectionLevel == PermissionInfo.PROTECTION_SIGNATURE
                        || p.info.protectionLevel == PermissionInfo.PROTECTION_SIGNATURE_OR_SYSTEM) {
                    allowed = (checkSignaturesLP(p.owner, pkg)
                                    == PackageManager.SIGNATURE_MATCH)
                            || (checkSignaturesLP(mPlatformPackage, pkg)
                                    == PackageManager.SIGNATURE_MATCH);
                    if (p.info.protectionLevel == PermissionInfo.PROTECTION_SIGNATURE_OR_SYSTEM) {
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
                        if (!gp.loadedPermissions.contains(perm)) {
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
                                    Log.i(TAG, "Auto-granting WRITE_EXTERNAL_STORAGE to old pkg "
                                            + pkg.packageName);
                                    break;
                                }
                            }
                        }
                    }
                    if (allowed) {
                        if (!gp.grantedPermissions.contains(perm)) {
                            addedPermission = true;
                            gp.grantedPermissions.add(perm);
                            gp.gids = appendInts(gp.gids, bp.gids);
                        }
                    } else {
                        Log.w(TAG, "Not granting permission " + perm
                                + " to package " + pkg.packageName
                                + " because it was previously installed without");
                    }
                } else {
                    Log.w(TAG, "Not granting permission " + perm
                            + " to package " + pkg.packageName
                            + " (protectionLevel=" + p.info.protectionLevel
                            + " flags=0x" + Integer.toHexString(pkg.applicationInfo.flags)
                            + ")");
                }
            } else {
                Log.w(TAG, "Unknown permission " + name
                        + " in package " + pkg.packageName);
            }
        }
        
        if ((addedPermission || replace) && !ps.permissionsFixed &&
                (ps.pkgFlags&ApplicationInfo.FLAG_SYSTEM) == 0) {
            // This is the first that we have heard about this package, so the
            // permissions we have now selected are fixed until explicitly
            // changed.
            ps.permissionsFixed = true;
            gp.loadedPermissions = new HashSet<String>(gp.grantedPermissions);
        }
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
            mActivities.put(a.component, a);
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
            mActivities.remove(a.component);
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
                    out.println(filter.activity.componentShortName);
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
            mServices.put(s.component, s);
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
            mServices.remove(s.component);
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
                    out.println(filter.service.componentShortName);
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

    private static final void sendPackageBroadcast(String action, String pkg, Bundle extras) {
        IActivityManager am = ActivityManagerNative.getDefault();
        if (am != null) {
            try {
                final Intent intent = new Intent(action,
                        pkg != null ? Uri.fromParts("package", pkg, null) : null);
                if (extras != null) {
                    intent.putExtras(extras);
                }
                intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                am.broadcastIntent(
                    null, intent,
                            null, null, 0, null, null, null, false, false);
            } catch (RemoteException ex) {
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

                if ((event&REMOVE_EVENTS) != 0) {
                    synchronized (mInstallLock) {
                        PackageParser.Package p = mAppDirs.get(fullPathStr);
                        if (p != null) {
                            removePackageLI(p, true);
                            removedPackage = p.applicationInfo.packageName;
                            removedUid = p.applicationInfo.uid;
                        }
                    }
                }

                if ((event&ADD_EVENTS) != 0) {
                    PackageParser.Package p = mAppDirs.get(fullPathStr);
                    if (p == null) {
                        p = scanPackageLI(fullPath, fullPath, fullPath,
                                (mIsRom ? PackageParser.PARSE_IS_SYSTEM : 0) |
                                PackageParser.PARSE_CHATTY |
                                PackageParser.PARSE_MUST_BE_APK,
                                SCAN_MONITOR);
                        if (p != null) {
                            synchronized (mPackages) {
                                grantPermissionsLP(p, false);
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
                sendPackageBroadcast(Intent.ACTION_PACKAGE_REMOVED, removedPackage, extras);
            }
            if (addedPackage != null) {
                Bundle extras = new Bundle(1);
                extras.putInt(Intent.EXTRA_UID, addedUid);
                sendPackageBroadcast(Intent.ACTION_PACKAGE_ADDED, addedPackage, extras);
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
        
        // Queue up an async operation since the package installation may take a little while.
        mHandler.post(new Runnable() {
            public void run() {
                mHandler.removeCallbacks(this);
                PackageInstalledInfo res;
                synchronized (mInstallLock) {
                    res = installPackageLI(packageURI, flags, true, installerPackageName);
                }
                if (observer != null) {
                    try {
                        observer.packageInstalled(res.name, res.returnCode);
                    } catch (RemoteException e) {
                        Log.i(TAG, "Observer no longer exists.");
                    }
                }
                // There appears to be a subtle deadlock condition if the sendPackageBroadcast
                // call appears in the synchronized block above.
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
                                         extras);
                    if (update) {
                        sendPackageBroadcast(Intent.ACTION_PACKAGE_REPLACED,
                                res.pkg.applicationInfo.packageName,
                                extras);
                    }
                }
                Runtime.getRuntime().gc();
            }
        });
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
    private void installNewPackageLI(String pkgName,
            File tmpPackageFile, 
            String destFilePath, File destPackageFile, File destResourceFile,
            PackageParser.Package pkg, boolean forwardLocked, boolean newInstall,
            String installerPackageName, PackageInstalledInfo res) {
        // Remember this for later, in case we need to rollback this install
        boolean dataDirExists = (new File(mAppDataDir, pkgName)).exists();
        res.name = pkgName;
        synchronized(mPackages) {
            if (mPackages.containsKey(pkgName) || mAppDirs.containsKey(destFilePath)) {
                // Don't allow installation over an existing package with the same name.
                Log.w(TAG, "Attempt to re-install " + pkgName 
                        + " without first uninstalling.");
                res.returnCode = PackageManager.INSTALL_FAILED_ALREADY_EXISTS;
                return;
            }
        }
        if (destPackageFile.exists()) {
            // It's safe to do this because we know (from the above check) that the file
            // isn't currently used for an installed package.
            destPackageFile.delete();
        }
        mLastScanError = PackageManager.INSTALL_SUCCEEDED;
        PackageParser.Package newPackage = scanPackageLI(tmpPackageFile, destPackageFile,
                destResourceFile, pkg, 0,
                SCAN_MONITOR | SCAN_FORCE_DEX
                | SCAN_UPDATE_SIGNATURE 
                | (forwardLocked ? SCAN_FORWARD_LOCKED : 0)
                | (newInstall ? SCAN_NEW_INSTALL : 0));
        if (newPackage == null) {
            Log.w(TAG, "Package couldn't be installed in " + destPackageFile);
            if ((res.returnCode=mLastScanError) == PackageManager.INSTALL_SUCCEEDED) {
                res.returnCode = PackageManager.INSTALL_FAILED_INVALID_APK;
            }
        } else {
            updateSettingsLI(pkgName, tmpPackageFile, 
                    destFilePath, destPackageFile,
                    destResourceFile, pkg, 
                    newPackage,
                    true,
                    forwardLocked,
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
                        pkgName, true,
                        dataDirExists ? PackageManager.DONT_DELETE_DATA : 0,
                                res.removedInfo);
            }
        }
    }
    
    private void replacePackageLI(String pkgName,
            File tmpPackageFile, 
            String destFilePath, File destPackageFile, File destResourceFile,
            PackageParser.Package pkg, boolean forwardLocked, boolean newInstall,
            String installerPackageName, PackageInstalledInfo res) {

        PackageParser.Package oldPackage;
        // First find the old package info and check signatures
        synchronized(mPackages) {
            oldPackage = mPackages.get(pkgName);
            if(checkSignaturesLP(pkg, oldPackage) != PackageManager.SIGNATURE_MATCH) {
                res.returnCode = PackageManager.INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES;
                return;
            }
        }
        boolean sysPkg = ((oldPackage.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
        if(sysPkg) {
            replaceSystemPackageLI(oldPackage,
                    tmpPackageFile, destFilePath, 
                    destPackageFile, destResourceFile, pkg, forwardLocked,
                    newInstall, installerPackageName, res);
        } else {
            replaceNonSystemPackageLI(oldPackage, tmpPackageFile, destFilePath,
                    destPackageFile, destResourceFile, pkg, forwardLocked,
                    newInstall, installerPackageName, res);
        }
    }
    
    private void replaceNonSystemPackageLI(PackageParser.Package deletedPackage,
            File tmpPackageFile, 
            String destFilePath, File destPackageFile, File destResourceFile,
            PackageParser.Package pkg, boolean forwardLocked, boolean newInstall,
            String installerPackageName, PackageInstalledInfo res) {
        PackageParser.Package newPackage = null;
        String pkgName = deletedPackage.packageName;
        boolean deletedPkg = true;
        boolean updatedSettings = false;
        
        String oldInstallerPackageName = null;
        synchronized (mPackages) {
            oldInstallerPackageName = mSettings.getInstallerPackageName(pkgName);
        }
        
        int parseFlags = PackageManager.INSTALL_REPLACE_EXISTING;
        // First delete the existing package while retaining the data directory
        if (!deletePackageLI(pkgName, false, PackageManager.DONT_DELETE_DATA,
                res.removedInfo)) {
            // If the existing package was'nt successfully deleted
            res.returnCode = PackageManager.INSTALL_FAILED_REPLACE_COULDNT_DELETE;
            deletedPkg = false;
        } else {
            // Successfully deleted the old package. Now proceed with re-installation
            mLastScanError = PackageManager.INSTALL_SUCCEEDED;
            newPackage = scanPackageLI(tmpPackageFile, destPackageFile,
                    destResourceFile, pkg, parseFlags,
                    SCAN_MONITOR | SCAN_FORCE_DEX
                    | SCAN_UPDATE_SIGNATURE 
                    | (forwardLocked ? SCAN_FORWARD_LOCKED : 0)
                    | (newInstall ? SCAN_NEW_INSTALL : 0));
            if (newPackage == null) {
                    Log.w(TAG, "Package couldn't be installed in " + destPackageFile);
                if ((res.returnCode=mLastScanError) == PackageManager.INSTALL_SUCCEEDED) {
                    res.returnCode = PackageManager.INSTALL_FAILED_INVALID_APK;
                }
            } else {
                updateSettingsLI(pkgName, tmpPackageFile, 
                        destFilePath, destPackageFile,
                        destResourceFile, pkg, 
                        newPackage,
                        true,
                        forwardLocked,  
                        installerPackageName,
                        res);
                updatedSettings = true;
            }
        }

        if (res.returnCode == PackageManager.INSTALL_SUCCEEDED) {
            // If we deleted an exisiting package, the old source and resource files that we
            // were keeping around in case we needed them (see below) can now be deleted
            final ApplicationInfo deletedPackageAppInfo = deletedPackage.applicationInfo;
            final ApplicationInfo installedPackageAppInfo =
                newPackage.applicationInfo;
            if (!deletedPackageAppInfo.sourceDir
                    .equals(installedPackageAppInfo.sourceDir)) {
                new File(deletedPackageAppInfo.sourceDir).delete();
            }
            if (!deletedPackageAppInfo.publicSourceDir
                    .equals(installedPackageAppInfo.publicSourceDir)) {
                new File(deletedPackageAppInfo.publicSourceDir).delete();
            }
            //update signature on the new package setting
            //this should always succeed, since we checked the
            //signature earlier.
            synchronized(mPackages) {
                verifySignaturesLP(mSettings.mPackages.get(pkgName), pkg,
                        parseFlags, true);
            }
        } else {
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
                installPackageLI(
                        Uri.fromFile(new File(deletedPackage.mPath)),
                        isForwardLocked(deletedPackage)
                        ? PackageManager.INSTALL_FORWARD_LOCK
                                : 0, false, oldInstallerPackageName);
            }
        }
    }
    
    private void replaceSystemPackageLI(PackageParser.Package deletedPackage,
            File tmpPackageFile, 
            String destFilePath, File destPackageFile, File destResourceFile,
            PackageParser.Package pkg, boolean forwardLocked, boolean newInstall,
            String installerPackageName, PackageInstalledInfo res) {
        PackageParser.Package newPackage = null;
        boolean updatedSettings = false;
        int parseFlags = PackageManager.INSTALL_REPLACE_EXISTING |
                PackageParser.PARSE_IS_SYSTEM;
        String packageName = deletedPackage.packageName;
        res.returnCode = PackageManager.INSTALL_FAILED_REPLACE_COULDNT_DELETE;
        if (packageName == null) {
            Log.w(TAG, "Attempt to delete null packageName.");
            return;
        }
        PackageParser.Package oldPkg;
        PackageSetting oldPkgSetting;
        synchronized (mPackages) {
            oldPkg = mPackages.get(packageName);
            oldPkgSetting = mSettings.mPackages.get(packageName);  
            if((oldPkg == null) || (oldPkg.applicationInfo == null) ||
                    (oldPkgSetting == null)) {
                Log.w(TAG, "Could'nt find package:"+packageName+" information");
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
        newPackage = scanPackageLI(tmpPackageFile, destPackageFile,
                destResourceFile, pkg, parseFlags,
                SCAN_MONITOR | SCAN_FORCE_DEX
                | SCAN_UPDATE_SIGNATURE 
                | (forwardLocked ? SCAN_FORWARD_LOCKED : 0)
                | (newInstall ? SCAN_NEW_INSTALL : 0));
        if (newPackage == null) {
            Log.w(TAG, "Package couldn't be installed in " + destPackageFile);
            if ((res.returnCode=mLastScanError) == PackageManager.INSTALL_SUCCEEDED) {
                res.returnCode = PackageManager.INSTALL_FAILED_INVALID_APK;
            }
        } else {
            updateSettingsLI(packageName, tmpPackageFile, 
                    destFilePath, destPackageFile,
                    destResourceFile, pkg, 
                    newPackage,
                    true,
                    forwardLocked,
                    installerPackageName,
                    res);
            updatedSettings = true;
        }

        if (res.returnCode == PackageManager.INSTALL_SUCCEEDED) {
            //update signature on the new package setting
            //this should always succeed, since we checked the
            //signature earlier.
            synchronized(mPackages) {
                verifySignaturesLP(mSettings.mPackages.get(packageName), pkg,
                        parseFlags, true);
            }
        } else {
            // Re installation failed. Restore old information
            // Remove new pkg information
            if (newPackage != null) {
                removePackageLI(newPackage, true);
            }
            // Add back the old system package
            scanPackageLI(oldPkgSetting.codePath, oldPkgSetting.codePath, 
                    oldPkgSetting.resourcePath,
                    oldPkg, parseFlags,
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
        }
    }
    
    private void updateSettingsLI(String pkgName, File tmpPackageFile, 
            String destFilePath, File destPackageFile,
            File destResourceFile, 
            PackageParser.Package pkg, 
            PackageParser.Package newPackage,
            boolean replacingExistingPackage,
            boolean forwardLocked,  
            String installerPackageName, PackageInstalledInfo res) {
        synchronized (mPackages) {
            //write settings. the installStatus will be incomplete at this stage.
            //note that the new package setting would have already been
            //added to mPackages. It hasn't been persisted yet.
            mSettings.setInstallStatus(pkgName, PKG_INSTALL_INCOMPLETE);
            mSettings.writeLP();
        }

        int retCode = 0;
        if ((pkg.applicationInfo.flags&ApplicationInfo.FLAG_HAS_CODE) != 0) {
            retCode = mInstaller.movedex(tmpPackageFile.toString(),
                    destPackageFile.toString());
            if (retCode != 0) {
                Log.e(TAG, "Couldn't rename dex file: " + destPackageFile);
                res.returnCode =  PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE;
                return;
            }
        }
        // XXX There are probably some big issues here: upon doing
        // the rename, we have reached the point of no return (the
        // original .apk is gone!), so we can't fail.  Yet... we can.
        if (!tmpPackageFile.renameTo(destPackageFile)) {
            Log.e(TAG, "Couldn't move package file to: " + destPackageFile);
            res.returnCode =  PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE;
        } else {
            res.returnCode = setPermissionsLI(pkgName, newPackage, destFilePath, 
                    destResourceFile, 
                    forwardLocked);
            if(res.returnCode != PackageManager.INSTALL_SUCCEEDED) {
                return;
            } else {
                Log.d(TAG, "New package installed in " + destPackageFile);
            }
        }
        if(res.returnCode != PackageManager.INSTALL_SUCCEEDED) {
            if (mInstaller != null) {
                mInstaller.rmdex(tmpPackageFile.getPath());
            }
        }

        synchronized (mPackages) {
            grantPermissionsLP(newPackage, true);
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
    
    private PackageInstalledInfo installPackageLI(Uri pPackageURI,
            int pFlags, boolean newInstall, String installerPackageName) {
        File tmpPackageFile = null;
        String pkgName = null;
        boolean forwardLocked = false;
        boolean replacingExistingPackage = false;
        // Result object to be returned
        PackageInstalledInfo res = new PackageInstalledInfo();
        res.returnCode = PackageManager.INSTALL_SUCCEEDED;
        res.uid = -1;
        res.pkg = null;
        res.removedInfo = new PackageRemovedInfo();

        main_flow: try {
            tmpPackageFile = createTempPackageFile();
            if (tmpPackageFile == null) {
                res.returnCode = PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE;
                break main_flow;
            }
            tmpPackageFile.deleteOnExit();  // paranoia
            if (pPackageURI.getScheme().equals("file")) {
                final File srcPackageFile = new File(pPackageURI.getPath());
                // We copy the source package file to a temp file and then rename it to the
                // destination file in order to eliminate a window where the package directory
                // scanner notices the new package file but it's not completely copied yet.
                if (!FileUtils.copyFile(srcPackageFile, tmpPackageFile)) {
                    Log.e(TAG, "Couldn't copy package file to temp file.");
                    res.returnCode = PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE;
                    break main_flow;
                }
            } else if (pPackageURI.getScheme().equals("content")) {
                ParcelFileDescriptor fd;
                try {
                    fd = mContext.getContentResolver().openFileDescriptor(pPackageURI, "r");
                } catch (FileNotFoundException e) {
                    Log.e(TAG, "Couldn't open file descriptor from download service.");
                    res.returnCode = PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE;
                    break main_flow;
                }
                if (fd == null) {
                    Log.e(TAG, "Couldn't open file descriptor from download service (null).");
                    res.returnCode = PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE;
                    break main_flow;
                }
                if (Config.LOGV) {
                    Log.v(TAG, "Opened file descriptor from download service.");
                }
                ParcelFileDescriptor.AutoCloseInputStream
                        dlStream = new ParcelFileDescriptor.AutoCloseInputStream(fd);
                // We copy the source package file to a temp file and then rename it to the
                // destination file in order to eliminate a window where the package directory
                // scanner notices the new package file but it's not completely copied yet.
                if (!FileUtils.copyToFile(dlStream, tmpPackageFile)) {
                    Log.e(TAG, "Couldn't copy package stream to temp file.");
                    res.returnCode = PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE;
                    break main_flow;
                }
            } else {
                Log.e(TAG, "Package URI is not 'file:' or 'content:' - " + pPackageURI);
                res.returnCode = PackageManager.INSTALL_FAILED_INVALID_URI;
                break main_flow;
            }
            pkgName = PackageParser.parsePackageName(
                    tmpPackageFile.getAbsolutePath(), 0);
            if (pkgName == null) {
                Log.e(TAG, "Couldn't find a package name in : " + tmpPackageFile);
                res.returnCode = PackageManager.INSTALL_FAILED_INVALID_APK;
                break main_flow;
            }
            res.name = pkgName;
            //initialize some variables before installing pkg
            final String pkgFileName = pkgName + ".apk";
            final File destDir = ((pFlags&PackageManager.INSTALL_FORWARD_LOCK) != 0)
                                 ?  mDrmAppPrivateInstallDir
                                 : mAppInstallDir;
            final File destPackageFile = new File(destDir, pkgFileName);
            final String destFilePath = destPackageFile.getAbsolutePath();
            File destResourceFile;
            if ((pFlags&PackageManager.INSTALL_FORWARD_LOCK) != 0) {
                final String publicZipFileName = pkgName + ".zip";
                destResourceFile = new File(mAppInstallDir, publicZipFileName);
                forwardLocked = true;
            } else {
                destResourceFile = destPackageFile;
            }
            // Retrieve PackageSettings and parse package
            int parseFlags = PackageParser.PARSE_CHATTY;
            parseFlags |= mDefParseFlags;
            PackageParser pp = new PackageParser(tmpPackageFile.getPath());
            pp.setSeparateProcesses(mSeparateProcesses);
            pp.setSdkVersion(mSdkVersion, mSdkCodename);
            final PackageParser.Package pkg = pp.parsePackage(tmpPackageFile,
                    destPackageFile.getAbsolutePath(), mMetrics, parseFlags);
            if (pkg == null) {
                res.returnCode = pp.getParseError();
                break main_flow;
            }
            if ((pkg.applicationInfo.flags&ApplicationInfo.FLAG_TEST_ONLY) != 0) {
                if ((pFlags&PackageManager.INSTALL_ALLOW_TEST) == 0) {
                    res.returnCode = PackageManager.INSTALL_FAILED_TEST_ONLY;
                    break main_flow;
                }
            }
            if (GET_CERTIFICATES && !pp.collectCertificates(pkg, parseFlags)) {
                res.returnCode = pp.getParseError();
                break main_flow;
            }
            
            synchronized (mPackages) {
                //check if installing already existing package
                if ((pFlags&PackageManager.INSTALL_REPLACE_EXISTING) != 0
                        && mPackages.containsKey(pkgName)) {
                    replacingExistingPackage = true;
                }
            }
            
            if(replacingExistingPackage) {
                replacePackageLI(pkgName,
                        tmpPackageFile, 
                        destFilePath, destPackageFile, destResourceFile,
                        pkg, forwardLocked, newInstall, installerPackageName,
                        res);
            } else {
                installNewPackageLI(pkgName,
                        tmpPackageFile, 
                        destFilePath, destPackageFile, destResourceFile,
                        pkg, forwardLocked, newInstall, installerPackageName,
                        res);
            }
        } finally {
            if (tmpPackageFile != null && tmpPackageFile.exists()) {
                tmpPackageFile.delete();
            }
        }
        return res;
    }
    
    private int setPermissionsLI(String pkgName,
            PackageParser.Package newPackage,
            String destFilePath,
            File destResourceFile,
            boolean forwardLocked) {
        int retCode;
        if (forwardLocked) {
            try {
                extractPublicFiles(newPackage, destResourceFile);
            } catch (IOException e) {
                Log.e(TAG, "Couldn't create a new zip file for the public parts of a" +
                           " forward-locked app.");
                return PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE;
            } finally {
                //TODO clean up the extracted public files
            }
            if (mInstaller != null) {
                retCode = mInstaller.setForwardLockPerm(pkgName,
                        newPackage.applicationInfo.uid);
            } else {
                final int filePermissions =
                        FileUtils.S_IRUSR|FileUtils.S_IWUSR|FileUtils.S_IRGRP;
                retCode = FileUtils.setPermissions(destFilePath, filePermissions, -1,
                                                   newPackage.applicationInfo.uid);
            }
        } else {
            final int filePermissions =
                    FileUtils.S_IRUSR|FileUtils.S_IWUSR|FileUtils.S_IRGRP
                    |FileUtils.S_IROTH;
            retCode = FileUtils.setPermissions(destFilePath, filePermissions, -1, -1);
        }
        if (retCode != 0) {
            Log.e(TAG, "Couldn't set new package file permissions for " + destFilePath
                       + ". The return code was: " + retCode);
        }
        return PackageManager.INSTALL_SUCCEEDED;
    }

    private boolean isForwardLocked(PackageParser.Package deletedPackage) {
        final ApplicationInfo applicationInfo = deletedPackage.applicationInfo;
        return applicationInfo.sourceDir.startsWith(mDrmAppPrivateInstallDir.getAbsolutePath());
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

    private File createTempPackageFile() {
        File tmpPackageFile;
        try {
            tmpPackageFile = File.createTempFile("vmdl", ".tmp", mAppInstallDir);
        } catch (IOException e) {
            Log.e(TAG, "Couldn't create temp file for downloaded package file.");
            return null;
        }
        try {
            FileUtils.setPermissions(
                    tmpPackageFile.getCanonicalPath(), FileUtils.S_IRUSR|FileUtils.S_IWUSR,
                    -1, -1);
        } catch (IOException e) {
            Log.e(TAG, "Trouble getting the canoncical path for a temp file.");
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

        synchronized (mInstallLock) {
            res = deletePackageLI(packageName, deleteCodeAndResources, flags, info);
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

                sendPackageBroadcast(Intent.ACTION_PACKAGE_ADDED, packageName, extras);
                sendPackageBroadcast(Intent.ACTION_PACKAGE_REPLACED, packageName, extras);
            }
        }
        return res;
    }

    static class PackageRemovedInfo {
        String removedPackage;
        int uid = -1;
        int removedUid = -1;
        boolean isRemovedPackageSystemUpdate = false;

        void sendBroadcast(boolean fullRemove, boolean replacing) {
            Bundle extras = new Bundle(1);
            extras.putInt(Intent.EXTRA_UID, removedUid >= 0 ? removedUid : uid);
            extras.putBoolean(Intent.EXTRA_DATA_REMOVED, fullRemove);
            if (replacing) {
                extras.putBoolean(Intent.EXTRA_REPLACING, true);
            }
            if (removedPackage != null) {
                sendPackageBroadcast(Intent.ACTION_PACKAGE_REMOVED, removedPackage, extras);
            }
            if (removedUid >= 0) {
                sendPackageBroadcast(Intent.ACTION_UID_REMOVED, null, extras);
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
        outInfo.removedPackage = packageName;
        removePackageLI(p, true);
        // Retrieve object to delete permissions for shared user later on
        PackageSetting deletedPs;
        synchronized (mPackages) {
            deletedPs = mSettings.mPackages.get(packageName);
        }
        if ((flags&PackageManager.DONT_DELETE_DATA) == 0) {
            if (mInstaller != null) {
                int retCode = mInstaller.remove(packageName);
                if (retCode < 0) {
                    Log.w(TAG, "Couldn't remove app data or cache directory for package: "
                               + packageName + ", retcode=" + retCode);
                    // we don't consider this to be a failure of the core package deletion
                }
            } else {
                //for emulator
                PackageParser.Package pkg = mPackages.get(packageName);
                File dataDir = new File(pkg.applicationInfo.dataDir);
                dataDir.delete();
            }
            synchronized (mPackages) {
                outInfo.removedUid = mSettings.removePackageLP(packageName);                
            }
        }
        synchronized (mPackages) {
            if ( (deletedPs != null) && (deletedPs.sharedUser != null)) {
                // remove permissions associated with package
                mSettings.updateSharedUserPerms (deletedPs);
            }
            // Save settings now
            mSettings.writeLP ();
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
            Log.w(TAG, "Package " + p.packageName + " has no applicationInfo.");
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
            Log.w(TAG, "Attempt to delete system package "+ p.packageName);
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
        }
        // Install the system package
        PackageParser.Package newPkg = scanPackageLI(ps.codePath, ps.codePath, ps.resourcePath,
                PackageParser.PARSE_MUST_BE_APK | PackageParser.PARSE_IS_SYSTEM,
                SCAN_MONITOR);
        
        if (newPkg == null) {
            Log.w(TAG, "Failed to restore system package:"+p.packageName+" with error:" + mLastScanError);
            return false;
        }
        synchronized (mPackages) {
            grantPermissionsLP(newPkg, true);
            mSettings.writeLP();
        }
        return true;
    }
    
    private void deletePackageResourcesLI(String packageName,
            String sourceDir, String publicSourceDir) {
        File sourceFile = new File(sourceDir);
        if (!sourceFile.exists()) {
            Log.w(TAG, "Package source " + sourceDir + " does not exist.");
        }
        // Delete application's code and resources
        sourceFile.delete();
        final File publicSourceFile = new File(publicSourceDir);
        if (publicSourceFile.exists()) {
            publicSourceFile.delete();
        }
        if (mInstaller != null) {
            int retCode = mInstaller.rmdex(sourceFile.toString());
            if (retCode < 0) {
                Log.w(TAG, "Couldn't remove dex file for package: "
                        + packageName + " at location " + sourceFile.toString() + ", retcode=" + retCode);
                // we don't consider this to be a failure of the core package deletion
            }
        }
    }
    
    private boolean deleteInstalledPackageLI(PackageParser.Package p,
            boolean deleteCodeAndResources, int flags, PackageRemovedInfo outInfo) {
        ApplicationInfo applicationInfo = p.applicationInfo;
        if (applicationInfo == null) {
            Log.w(TAG, "Package " + p.packageName + " has no applicationInfo.");
            return false;
        }
        outInfo.uid = applicationInfo.uid;

        // Delete package data from internal structures and also remove data if flag is set
        removePackageDataLI(p, outInfo, flags);

        // Delete application code and resources
        if (deleteCodeAndResources) {
            deletePackageResourcesLI(applicationInfo.packageName,
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
            Log.w(TAG, "Attempt to delete null packageName.");
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
                    Log.w(TAG, "Package named '" + packageName +"' doesn't exist.");
                    return false;
                }
                p = ps.pkg;
            }
        }
        if (p == null) {
            Log.w(TAG, "Package named '" + packageName +"' doesn't exist.");
            return false;
        }
        
        if (dataOnly) {
            // Delete application data first
            removePackageDataLI(p, outInfo, flags);
            return true;
        }
        // At this point the package should have ApplicationInfo associated with it
        if (p.applicationInfo == null) {
            Log.w(TAG, "Package " + p.packageName + " has no applicationInfo.");
            return false;
        }
        if ( (p.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
            Log.i(TAG, "Removing system package:"+p.packageName);
            // When an updated system application is deleted we delete the existing resources as well and
            // fall back to existing code in system partition
            return deleteSystemPackageLI(p, flags, outInfo);
        }
        Log.i(TAG, "Removing non-system package:"+p.packageName);
        return deleteInstalledPackageLI (p, deleteCodeAndResources, flags, outInfo);
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
            Log.w(TAG, "Attempt to delete null packageName.");
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
                    Log.w(TAG, "Package named '" + packageName +"' doesn't exist.");
                    return false;
                }
                p = ps.pkg;
            }
        }
        if(!dataOnly) {
            //need to check this only for fully installed applications
            if (p == null) {
                Log.w(TAG, "Package named '" + packageName +"' doesn't exist.");
                return false;
            }
            final ApplicationInfo applicationInfo = p.applicationInfo;
            if (applicationInfo == null) {
                Log.w(TAG, "Package " + packageName + " has no applicationInfo.");
                return false;
            }
        }
        if (mInstaller != null) {
            int retCode = mInstaller.clearUserData(packageName);
            if (retCode < 0) {
                Log.w(TAG, "Couldn't remove cache files for package: "
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
            Log.w(TAG, "Attempt to delete null packageName.");
            return false;
        }
        PackageParser.Package p;
        synchronized (mPackages) {
            p = mPackages.get(packageName);
        }
        if (p == null) {
            Log.w(TAG, "Package named '" + packageName +"' doesn't exist.");
            return false;
        }
        final ApplicationInfo applicationInfo = p.applicationInfo;
        if (applicationInfo == null) {
            Log.w(TAG, "Package " + packageName + " has no applicationInfo.");
            return false;
        }
        if (mInstaller != null) {
            int retCode = mInstaller.deleteCacheFiles(packageName);
            if (retCode < 0) {
                Log.w(TAG, "Couldn't remove cache files for package: "
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
            Log.w(TAG, "Attempt to get size of null packageName.");
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
                    Log.w(TAG, "Package named '" + packageName +"' doesn't exist.");
                    return false;
                }
                p = ps.pkg;
            }
        }
        String publicSrcDir = null;
        if(!dataOnly) {
            final ApplicationInfo applicationInfo = p.applicationInfo;
            if (applicationInfo == null) {
                Log.w(TAG, "Package " + packageName + " has no applicationInfo.");
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
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.SET_PREFERRED_APPLICATIONS, null);

        synchronized (mPackages) {
            PackageParser.Package p = mPackages.get(packageName);
            if (p == null) {
                return;
            }
            PackageSetting ps = (PackageSetting)p.mExtras;
            if (ps != null) {
                mSettings.mPreferredPackages.remove(ps);
                mSettings.mPreferredPackages.add(0, ps);
                updatePreferredIndicesLP();
                mSettings.writeLP();
            }
        }
    }

    public void removePackageFromPreferred(String packageName) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.SET_PREFERRED_APPLICATIONS, null);

        synchronized (mPackages) {
            PackageParser.Package p = mPackages.get(packageName);
            if (p == null) {
                return;
            }
            if (p.mPreferredOrder > 0) {
                PackageSetting ps = (PackageSetting)p.mExtras;
                if (ps != null) {
                    mSettings.mPreferredPackages.remove(ps);
                    p.mPreferredOrder = 0;
                    updatePreferredIndicesLP();
                    mSettings.writeLP();
                }
            }
        }
    }

    private void updatePreferredIndicesLP() {
        final ArrayList<PackageSetting> pkgs
                = mSettings.mPreferredPackages;
        final int N = pkgs.size();
        for (int i=0; i<N; i++) {
            pkgs.get(i).pkg.mPreferredOrder = N - i;
        }
    }

    public List<PackageInfo> getPreferredPackages(int flags) {
        synchronized (mPackages) {
            final ArrayList<PackageInfo> res = new ArrayList<PackageInfo>();
            final ArrayList<PackageSetting> pref = mSettings.mPreferredPackages;
            final int N = pref.size();
            for (int i=0; i<N; i++) {
                res.add(generatePackageInfo(pref.get(i).pkg, flags));
            }
            return res;
        }
    }

    public void addPreferredActivity(IntentFilter filter, int match,
            ComponentName[] set, ComponentName activity) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.SET_PREFERRED_APPLICATIONS, null);

        synchronized (mPackages) {
            Log.i(TAG, "Adding preferred activity " + activity + ":");
            filter.dump(new LogPrinter(Log.INFO, TAG), "  ");
            mSettings.mPreferredActivities.addFilter(
                    new PreferredActivity(filter, match, set, activity));
            mSettings.writeLP();
        }
    }

    public void replacePreferredActivity(IntentFilter filter, int match,
            ComponentName[] set, ComponentName activity) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.SET_PREFERRED_APPLICATIONS, null);
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
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.SET_PREFERRED_APPLICATIONS, null);

        synchronized (mPackages) {
            if (clearPackagePreferredActivitiesLP(packageName)) {
                mSettings.writeLP();
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
            final String packageNameStr, String classNameStr, int newState, final int flags) {
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
        int packageUid = -1;
        synchronized (mPackages) {
            pkgSetting = mSettings.mPackages.get(packageNameStr);
            if (pkgSetting == null) {
                if (classNameStr == null) {
                    throw new IllegalArgumentException(
                            "Unknown package: " + packageNameStr);
                }
                throw new IllegalArgumentException(
                        "Unknown component: " + packageNameStr
                        + "/" + classNameStr);
            }
            if (!allowedByPermission && (uid != pkgSetting.userId)) {
                throw new SecurityException(
                        "Permission Denial: attempt to change component state from pid="
                        + Binder.getCallingPid()
                        + ", uid=" + uid + ", package uid=" + pkgSetting.userId);
            }
            packageUid = pkgSetting.userId;
            if (classNameStr == null) {
                // We're dealing with an application/package level state change
                pkgSetting.enabled = newState;
            } else {
                // We're dealing with a component level state change
                switch (newState) {
                case COMPONENT_ENABLED_STATE_ENABLED:
                    pkgSetting.enableComponentLP(classNameStr);
                    break;
                case COMPONENT_ENABLED_STATE_DISABLED:
                    pkgSetting.disableComponentLP(classNameStr);
                    break;
                case COMPONENT_ENABLED_STATE_DEFAULT:
                    pkgSetting.restoreComponentLP(classNameStr);
                    break;
                default:
                    Log.e(TAG, "Invalid new component state: " + newState);
                }
            }
            mSettings.writeLP();
        }
        
        long callingId = Binder.clearCallingIdentity();
        try {
            Bundle extras = new Bundle(2);
            extras.putBoolean(Intent.EXTRA_DONT_KILL_APP,
                    (flags&PackageManager.DONT_KILL_APP) != 0);
            extras.putInt(Intent.EXTRA_UID, packageUid);
            sendPackageBroadcast(Intent.ACTION_PACKAGE_CHANGED, packageNameStr, extras);
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
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
        mCompatibilityModeEnabled = android.provider.Settings.System.getInt(
                mContext.getContentResolver(),
                android.provider.Settings.System.COMPATIBILITY_MODE, 1) == 1;
        if (DEBUG_SETTINGS) {
            Log.d(TAG, "compatibility mode:" + mCompatibilityModeEnabled);
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

        synchronized (mPackages) {
            pw.println("Activity Resolver Table:");
            mActivities.dump(pw, "  ");
            pw.println(" ");
            pw.println("Receiver Resolver Table:");
            mReceivers.dump(pw, "  ");
            pw.println(" ");
            pw.println("Service Resolver Table:");
            mServices.dump(pw, "  ");
            pw.println(" ");
            pw.println("Preferred Activities:");
            mSettings.mPreferredActivities.dump(pw, "  ");
            pw.println(" ");
            pw.println("Preferred Packages:");
            {
                for (PackageSetting ps : mSettings.mPreferredPackages) {
                    pw.print("  "); pw.println(ps.name);
                }
            }
            pw.println(" ");
            pw.println("Permissions:");
            {
                for (BasePermission p : mSettings.mPermissions.values()) {
                    pw.print("  Permission ["); pw.print(p.name); pw.print("] (");
                            pw.print(Integer.toHexString(System.identityHashCode(p)));
                            pw.println("):");
                    pw.print("    sourcePackage="); pw.println(p.sourcePackage);
                    pw.print("    uid="); pw.print(p.uid);
                            pw.print(" gids="); pw.print(arrayToString(p.gids));
                            pw.print(" type="); pw.println(p.type);
                }
            }
            pw.println(" ");
            pw.println("Packages:");
            {
                for (PackageSetting ps : mSettings.mPackages.values()) {
                    pw.print("  Package ["); pw.print(ps.name); pw.print("] (");
                            pw.print(Integer.toHexString(System.identityHashCode(ps)));
                            pw.println("):");
                    pw.print("    userId="); pw.print(ps.userId);
                            pw.print(" gids="); pw.println(arrayToString(ps.gids));
                    pw.print("    sharedUser="); pw.println(ps.sharedUser);
                    pw.print("    pkg="); pw.println(ps.pkg);
                    pw.print("    codePath="); pw.println(ps.codePathString);
                    pw.print("    resourcePath="); pw.println(ps.resourcePathString);
                    if (ps.pkg != null) {
                        pw.print("    dataDir="); pw.println(ps.pkg.applicationInfo.dataDir);
                    }
                    pw.print("    timeStamp="); pw.println(ps.getTimeStampStr());
                    pw.print("    signatures="); pw.println(ps.signatures);
                    pw.print("    permissionsFixed="); pw.print(ps.permissionsFixed);
                            pw.print(" pkgFlags=0x"); pw.print(Integer.toHexString(ps.pkgFlags));
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
                    if (ps.loadedPermissions.size() > 0) {
                        pw.println("    loadedPermissions:");
                        for (String s : ps.loadedPermissions) {
                            pw.print("      "); pw.println(s);
                        }
                    }
                }
            }
            pw.println(" ");
            pw.println("Shared Users:");
            {
                for (SharedUserSetting su : mSettings.mSharedUsers.values()) {
                    pw.print("  SharedUser ["); pw.print(su.name); pw.print("] (");
                            pw.print(Integer.toHexString(System.identityHashCode(su)));
                            pw.println("):");
                    pw.print("    userId="); pw.print(su.userId);
                            pw.print(" gids="); pw.println(arrayToString(su.gids));
                    pw.println("    grantedPermissions:");
                    for (String s : su.grantedPermissions) {
                        pw.print("      "); pw.println(s);
                    }
                    pw.println("    loadedPermissions:");
                    for (String s : su.loadedPermissions) {
                        pw.print("      "); pw.println(s);
                    }
                }
            }
            pw.println(" ");
            pw.println("Settings parse messages:");
            pw.println(mSettings.mReadMessages.toString());
        }
    }

    static final class BasePermission {
        final static int TYPE_NORMAL = 0;
        final static int TYPE_BUILTIN = 1;
        final static int TYPE_DYNAMIC = 2;

        final String name;
        final String sourcePackage;
        final int type;
        PackageParser.Permission perm;
        PermissionInfo pendingInfo;
        int uid;
        int[] gids;

        BasePermission(String _name, String _sourcePackage, int _type) {
            name = _name;
            sourcePackage = _sourcePackage;
            type = _type;
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

        /**
         * If any of the given 'sigs' is contained in the existing signatures,
         * then completely replace the current signatures with the ones in
         * 'sigs'.  This is used for updating an existing package to a newly
         * installed version.
         */
        boolean updateSignatures(Signature[] sigs, boolean update) {
            if (mSignatures == null) {
                if (update) {
                    assignSignatures(sigs);
                }
                return true;
            }
            if (sigs == null) {
                return false;
            }

            for (int i=0; i<sigs.length; i++) {
                Signature sig = sigs[i];
                for (int j=0; j<mSignatures.length; j++) {
                    if (mSignatures[j].equals(sig)) {
                        if (update) {
                            assignSignatures(sigs);
                        }
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * If any of the given 'sigs' is contained in the existing signatures,
         * then add in any new signatures found in 'sigs'.  This is used for
         * including a new package into an existing shared user id.
         */
        boolean mergeSignatures(Signature[] sigs, boolean update) {
            if (mSignatures == null) {
                if (update) {
                    assignSignatures(sigs);
                }
                return true;
            }
            if (sigs == null) {
                return false;
            }

            Signature[] added = null;
            int addedCount = 0;
            boolean haveMatch = false;
            for (int i=0; i<sigs.length; i++) {
                Signature sig = sigs[i];
                boolean found = false;
                for (int j=0; j<mSignatures.length; j++) {
                    if (mSignatures[j].equals(sig)) {
                        found = true;
                        haveMatch = true;
                        break;
                    }
                }

                if (!found) {
                    if (added == null) {
                        added = new Signature[sigs.length];
                    }
                    added[i] = sig;
                    addedCount++;
                }
            }

            if (!haveMatch) {
                // Nothing matched -- reject the new signatures.
                return false;
            }
            if (added == null) {
                // Completely matched -- nothing else to do.
                return true;
            }

            // Add additional signatures in.
            if (update) {
                Signature[] total = new Signature[addedCount+mSignatures.length];
                System.arraycopy(mSignatures, 0, total, 0, mSignatures.length);
                int j = mSignatures.length;
                for (int i=0; i<added.length; i++) {
                    if (added[i] != null) {
                        total[j] = added[i];
                        j++;
                    }
                }
                mSignatures = total;
            }
            return true;
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
        final int pkgFlags;
        
        HashSet<String> grantedPermissions = new HashSet<String>();
        int[] gids;
        
        HashSet<String> loadedPermissions = new HashSet<String>();
        
        GrantedPermissions(int pkgFlags) {
            this.pkgFlags = pkgFlags & ApplicationInfo.FLAG_SYSTEM;
        }
    }
    
    /**
     * Settings base class for pending and resolved classes.
     */
    static class PackageSettingBase extends GrantedPermissions {
        final String name;
        final File codePath;
        final String codePathString;
        final File resourcePath;
        final String resourcePathString;
        private long timeStamp;
        private String timeStampString = "0";
        final int versionCode;

        PackageSignatures signatures = new PackageSignatures();

        boolean permissionsFixed;
        
        /* Explicitly disabled components */
        HashSet<String> disabledComponents = new HashSet<String>(0);
        /* Explicitly enabled components */
        HashSet<String> enabledComponents = new HashSet<String>(0);
        int enabled = COMPONENT_ENABLED_STATE_DEFAULT;
        int installStatus = PKG_INSTALL_COMPLETE;
        
        /* package name of the app that installed this package */
        String installerPackageName;

        PackageSettingBase(String name, File codePath, File resourcePath,
                int pVersionCode, int pkgFlags) {
            super(pkgFlags);
            this.name = name;
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
            loadedPermissions = base.loadedPermissions;
            
            timeStamp = base.timeStamp;
            timeStampString = base.timeStampString;
            signatures = base.signatures;
            permissionsFixed = base.permissionsFixed;
            disabledComponents = base.disabledComponents;
            enabledComponents = base.enabledComponents;
            enabled = base.enabled;
            installStatus = base.installStatus;
        }

        void enableComponentLP(String componentClassName) {
            disabledComponents.remove(componentClassName);
            enabledComponents.add(componentClassName);
        }

        void disableComponentLP(String componentClassName) {
            enabledComponents.remove(componentClassName);
            disabledComponents.add(componentClassName);
        }

        void restoreComponentLP(String componentClassName) {
            enabledComponents.remove(componentClassName);
            disabledComponents.remove(componentClassName);
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

        PackageSetting(String name, File codePath, File resourcePath,
                int pVersionCode, int pkgFlags) {
            super(name, codePath, resourcePath, pVersionCode, pkgFlags);
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
        private final HashMap<String, PackageSetting> mPackages =
                new HashMap<String, PackageSetting>();
        // The user's preferred packages/applications, in order of preference.
        // First is the most preferred.
        private final ArrayList<PackageSetting> mPreferredPackages =
                new ArrayList<PackageSetting>();
        // List of replaced system applications
        final HashMap<String, PackageSetting> mDisabledSysPackages =
            new HashMap<String, PackageSetting>();
        
        // The user's preferred activities associated with particular intent
        // filters.
        private final IntentResolver<PreferredActivity, PreferredActivity> mPreferredActivities =
                    new IntentResolver<PreferredActivity, PreferredActivity>() {
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

        private final ArrayList<String> mPendingPreferredPackages
                = new ArrayList<String>();

        private final StringBuilder mReadMessages = new StringBuilder();

        private static final class PendingPackage extends PackageSettingBase {
            final int sharedId;

            PendingPackage(String name, File codePath, File resourcePath,
                    int sharedId, int pVersionCode, int pkgFlags) {
                super(name, codePath, resourcePath, pVersionCode, pkgFlags);
                this.sharedId = sharedId;
            }
        }
        private final ArrayList<PendingPackage> mPendingPackages
                = new ArrayList<PendingPackage>();

        Settings() {
            File dataDir = Environment.getDataDirectory();
            File systemDir = new File(dataDir, "system");
            systemDir.mkdirs();
            FileUtils.setPermissions(systemDir.toString(),
                    FileUtils.S_IRWXU|FileUtils.S_IRWXG
                    |FileUtils.S_IROTH|FileUtils.S_IXOTH,
                    -1, -1);
            mSettingsFilename = new File(systemDir, "packages.xml");
            mBackupSettingsFilename = new File(systemDir, "packages-backup.xml");
        }

        PackageSetting getPackageLP(PackageParser.Package pkg,
                SharedUserSetting sharedUser, File codePath, File resourcePath,
                int pkgFlags, boolean create, boolean add) {
            final String name = pkg.packageName;
            PackageSetting p = getPackageLP(name, sharedUser, codePath,
                    resourcePath, pkg.mVersionCode, pkgFlags, create, add);

            if (p != null) {
                p.pkg = pkg;
            }
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
            PackageSetting ret = addPackageLP(name, p.codePath,
                    p.resourcePath, p.userId, p.versionCode, p.pkgFlags);
            mDisabledSysPackages.remove(name);
            return ret;
        }
        
        PackageSetting addPackageLP(String name, File codePath,
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
            p = new PackageSetting(name, codePath, resourcePath, vc, pkgFlags);
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

        private PackageSetting getPackageLP(String name,
                SharedUserSetting sharedUser, File codePath, File resourcePath,
                int vc, int pkgFlags, boolean create, boolean add) {
            PackageSetting p = mPackages.get(name);
            if (p != null) {
                if (!p.codePath.equals(codePath)) {
                    // Check to see if its a disabled system app
                    PackageSetting ps = mDisabledSysPackages.get(name);
                    if((ps != null) && ((ps.pkgFlags & ApplicationInfo.FLAG_SYSTEM) != 0)) {
                        // This is an updated system app with versions in both system
                        // and data partition. Just let the most recent version
                        // take precedence.
                        return p;
                    } else if ((p.pkg != null) && (p.pkg.applicationInfo != null)) {
                        // Let the app continue with previous uid if code path changes.
                        reportSettingsProblem(Log.WARN,
                                "Package " + name + " codePath changed from " + p.codePath
                                + " to " + codePath + "; Retaining data and using new code");
                    }
                } else if (p.sharedUser != sharedUser) {
                    reportSettingsProblem(Log.WARN,
                            "Package " + name + " shared user changed from "
                            + (p.sharedUser != null ? p.sharedUser.name : "<nothing>")
                            + " to "
                            + (sharedUser != null ? sharedUser.name : "<nothing>")
                            + "; replacing with new");
                    p = null;
                }
            }
            if (p == null) {
                // Create a new PackageSettings entry. this can end up here because
                // of code path mismatch or user id mismatch of an updated system partition
                if (!create) {
                    return null;
                }
                p = new PackageSetting(name, codePath, resourcePath, vc, pkgFlags);
                p.setTimeStamp(codePath.lastModified());
                p.sharedUser = sharedUser;
                if (sharedUser != null) {
                    p.userId = sharedUser.userId;
                } else if (MULTIPLE_APPLICATION_UIDS) {
                    p.userId = newUserIdLP(p);
                } else {
                    p.userId = FIRST_APPLICATION_UID;
                }
                if (p.userId < 0) {
                    reportSettingsProblem(Log.WARN,
                            "Package " + name + " could not be assigned a valid uid");
                    return null;
                }
                if (add) {
                    // Finish adding new package by adding it and updating shared 
                    // user preferences
                    insertPackageSettingLP(p, name, sharedUser);
                }
            }
            return p;
        }
        
        // Utility method that adds a PackageSetting to mPackages and
        // completes updating the shared user attributes
        private void insertPackageSettingLP(PackageSetting p, String name,
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

        private void updateSharedUserPerms (PackageSetting deletedPs) {
            if ( (deletedPs == null) || (deletedPs.pkg == null)) {
                Log.i(TAG, "Trying to update info for null package. Just ignoring");
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
                    if (pkg.pkg.requestedPermissions.contains(eachPerm)) {
                        used = true;
                        break;
                    }
                }
                if (!used) {
                    // can safely delete this permission from list
                    sus.grantedPermissions.remove(eachPerm);
                    sus.loadedPermissions.remove(eachPerm);
                }
            }
            // Update gids
            int newGids[] = null;
            for (PackageSetting pkg:sus.packages) {
                newGids = appendInts(newGids, pkg.gids);
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
                            "Adding duplicate shared id: " + uid
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
                if (mBackupSettingsFilename.exists()) {
                    mBackupSettingsFilename.delete();
                }
                mSettingsFilename.renameTo(mBackupSettingsFilename);
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

                serializer.startTag(null, "preferred-packages");
                int N = mPreferredPackages.size();
                for (int i=0; i<N; i++) {
                    PackageSetting pkg = mPreferredPackages.get(i);
                    serializer.startTag(null, "item");
                    serializer.attribute(null, "name", pkg.name);
                    serializer.endTag(null, "item");
                }
                serializer.endTag(null, "preferred-packages");

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

            } catch(XmlPullParserException e) {
                Log.w(TAG, "Unable to write package manager settings, current changes will be lost at reboot", e);

            } catch(java.io.IOException e) {
                Log.w(TAG, "Unable to write package manager settings, current changes will be lost at reboot", e);

            }

            //Debug.stopMethodTracing();
        }
       
        void writeDisabledSysPackage(XmlSerializer serializer, final PackageSetting pkg) 
        throws java.io.IOException {
            serializer.startTag(null, "updated-package");
            serializer.attribute(null, "name", pkg.name);
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
                    if ((bp != null) && (bp.perm != null) && (bp.perm.info != null)) {
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
            serializer.attribute(null, "codePath", pkg.codePathString);
            if (!pkg.resourcePathString.equals(pkg.codePathString)) {
                serializer.attribute(null, "resourcePath", pkg.resourcePathString);
            }
            serializer.attribute(null, "system",
                    (pkg.pkgFlags&ApplicationInfo.FLAG_SYSTEM) != 0
                    ? "true" : "false");
            serializer.attribute(null, "ts", pkg.getTimeStampStr());
            serializer.attribute(null, "version", String.valueOf(pkg.versionCode));
            if (pkg.sharedUser == null) {
                serializer.attribute(null, "userId",
                        Integer.toString(pkg.userId));
            } else {
                serializer.attribute(null, "sharedUserId",
                        Integer.toString(pkg.userId));
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
                        if (pi.protectionLevel !=
                                PermissionInfo.PROTECTION_NORMAL) {
                            serializer.attribute(null, "protection",
                                    Integer.toString(pi.protectionLevel));
                        }
                    }
                }
                serializer.endTag(null, "item");
            }
        }

        String getReadMessagesLP() {
            return mReadMessages.toString();
        }

        ArrayList<String> getListOfIncompleteInstallPackages() {
            HashSet<String> kList = new HashSet<String>(mPackages.keySet());
            Iterator<String> its = kList.iterator();
            ArrayList<String> ret = new ArrayList<String>();
            while(its.hasNext()) {
                String key = its.next();
                PackageSetting ps = mPackages.get(key);
                if(ps.getInstallStatus() == PKG_INSTALL_INCOMPLETE) {
                    ret.add(key);
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
                } catch (java.io.IOException e) {
                    // We'll try for the normal settings file.
                }
            }

            mPastSignatures.clear();

            try {
                if (str == null) {
                    if (!mSettingsFilename.exists()) {
                        mReadMessages.append("No settings file found\n");
                        Log.i(TAG, "No current settings file!");
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
                    Log.e(TAG, "No start tag found in package manager settings");
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
                        readPreferredPackagesLP(parser);
                    } else if (tagName.equals("preferred-activities")) {
                        readPreferredActivitiesLP(parser);
                    } else if(tagName.equals("updated-package")) {
                        readDisabledSysPackageLP(parser);
                    } else {
                        Log.w(TAG, "Unknown element under <packages>: "
                              + parser.getName());
                        XmlUtils.skipCurrentTag(parser);
                    }
                }

                str.close();

            } catch(XmlPullParserException e) {
                mReadMessages.append("Error reading: " + e.toString());
                Log.e(TAG, "Error reading package manager settings", e);

            } catch(java.io.IOException e) {
                mReadMessages.append("Error reading: " + e.toString());
                Log.e(TAG, "Error reading package manager settings", e);

            }

            int N = mPendingPackages.size();
            for (int i=0; i<N; i++) {
                final PendingPackage pp = mPendingPackages.get(i);
                Object idObj = getUserIdLP(pp.sharedId);
                if (idObj != null && idObj instanceof SharedUserSetting) {
                    PackageSetting p = getPackageLP(pp.name,
                            (SharedUserSetting)idObj, pp.codePath, pp.resourcePath,
                            pp.versionCode, pp.pkgFlags, true, true);
                    if (p == null) {
                        Log.w(TAG, "Unable to create application package for "
                                + pp.name);
                        continue;
                    }
                    p.copyFrom(pp);
                } else if (idObj != null) {
                    String msg = "Bad package setting: package " + pp.name
                            + " has shared uid " + pp.sharedId
                            + " that is not a shared uid\n";
                    mReadMessages.append(msg);
                    Log.e(TAG, msg);
                } else {
                    String msg = "Bad package setting: package " + pp.name
                            + " has shared uid " + pp.sharedId
                            + " that is not defined\n";
                    mReadMessages.append(msg);
                    Log.e(TAG, msg);
                }
            }
            mPendingPackages.clear();

            N = mPendingPreferredPackages.size();
            mPreferredPackages.clear();
            for (int i=0; i<N; i++) {
                final String name = mPendingPreferredPackages.get(i);
                final PackageSetting p = mPackages.get(name);
                if (p != null) {
                    mPreferredPackages.add(p);
                } else {
                    Log.w(TAG, "Unknown preferred package: " + name);
                }
            }
            mPendingPreferredPackages.clear();

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
                        if (dynamic) {
                            PermissionInfo pi = new PermissionInfo();
                            pi.packageName = sourcePackage.intern();
                            pi.name = name.intern();
                            pi.icon = readInt(parser, null, "icon", 0);
                            pi.nonLocalizedLabel = parser.getAttributeValue(
                                    null, "label");
                            pi.protectionLevel = readInt(parser, null, "protection",
                                    PermissionInfo.PROTECTION_NORMAL);
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
            String codePathStr = parser.getAttributeValue(null, "codePath");
            String resourcePathStr = parser.getAttributeValue(null, "resourcePath");
            if(resourcePathStr == null) {
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
            PackageSetting ps = new PackageSetting(name, 
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
            String idStr = null;
            String sharedIdStr = null;
            String codePathStr = null;
            String resourcePathStr = null;
            String systemStr = null;
            String installerPackageName = null;
            int pkgFlags = 0;
            String timeStampStr;
            long timeStamp = 0;
            PackageSettingBase packageSetting = null;
            String version = null;
            int versionCode = 0;
            try {
                name = parser.getAttributeValue(null, "name");
                idStr = parser.getAttributeValue(null, "userId");
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
                systemStr = parser.getAttributeValue(null, "system");
                installerPackageName = parser.getAttributeValue(null, "installer");
                if (systemStr != null) {
                    if ("true".equals(systemStr)) {
                        pkgFlags |= ApplicationInfo.FLAG_SYSTEM;
                    }
                } else {
                    // Old settings that don't specify system...  just treat
                    // them as system, good enough.
                    pkgFlags |= ApplicationInfo.FLAG_SYSTEM;
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
                if (name == null) {
                    reportSettingsProblem(Log.WARN,
                            "Error in package manager settings: <package> has no name at "
                            + parser.getPositionDescription());
                } else if (codePathStr == null) {
                    reportSettingsProblem(Log.WARN,
                            "Error in package manager settings: <package> has no codePath at "
                            + parser.getPositionDescription());
                } else if (userId > 0) {
                    packageSetting = addPackageLP(name.intern(), new File(codePathStr), 
                            new File(resourcePathStr), userId, versionCode, pkgFlags);
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
                        packageSetting = new PendingPackage(name.intern(), new File(codePathStr),
                                new File(resourcePathStr), userId, versionCode, pkgFlags);
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
                                packageSetting.loadedPermissions);
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
                        readGrantedPermissionsLP(parser, su.loadedPermissions);
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

        private void readPreferredPackagesLP(XmlPullParser parser)
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
                    String name = parser.getAttributeValue(null, "name");
                    if (name != null) {
                        mPendingPreferredPackages.add(name);
                    } else {
                        reportSettingsProblem(Log.WARN,
                                "Error in package manager settings: <preferred-package> has no name at "
                                + parser.getPositionDescription());
                    }
                } else {
                    reportSettingsProblem(Log.WARN,
                            "Unknown element under <preferred-packages>: "
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
            return ((flags&PackageManager.GET_DISABLED_COMPONENTS) != 0)
                   || ((componentInfo.enabled
                        && ((packageSettings.enabled == COMPONENT_ENABLED_STATE_ENABLED)
                            || (componentInfo.applicationInfo.enabled
                                && packageSettings.enabled != COMPONENT_ENABLED_STATE_DISABLED))
                        && !packageSettings.disabledComponents.contains(componentInfo.name))
                       || packageSettings.enabledComponents.contains(componentInfo.name));
        }
    }
}
