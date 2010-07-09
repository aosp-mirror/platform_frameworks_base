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

package android.app;

import com.android.internal.policy.PolicyManager;
import com.android.internal.util.XmlUtils;
import com.google.android.collect.Maps;

import org.xmlpull.v1.XmlPullParserException;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.IContentProvider;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IIntentReceiver;
import android.content.IntentSender;
import android.content.ReceiverCallNotAllowedException;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.IPackageDataObserver;
import android.content.pm.IPackageDeleteObserver;
import android.content.pm.IPackageInstallObserver;
import android.content.pm.IPackageMoveObserver;
import android.content.pm.IPackageManager;
import android.content.pm.IPackageStatsObserver;
import android.content.pm.InstrumentationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDatabase.CursorFactory;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.hardware.SensorManager;
import android.location.ILocationManager;
import android.location.LocationManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.IConnectivityManager;
import android.net.ThrottleManager;
import android.net.IThrottleManager;
import android.net.Uri;
import android.net.wifi.IWifiManager;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.DropBoxManager;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.IBinder;
import android.os.IPowerManager;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.Vibrator;
import android.os.FileUtils.FileStatus;
import android.os.storage.StorageManager;
import android.telephony.TelephonyManager;
import android.text.ClipboardManager;
import android.util.AndroidRuntimeException;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.WindowManagerImpl;
import android.view.accessibility.AccessibilityManager;
import android.view.inputmethod.InputMethodManager;
import android.accounts.AccountManager;
import android.accounts.IAccountManager;
import android.app.admin.DevicePolicyManager;

import com.android.internal.os.IDropBoxManagerService;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.Map.Entry;

class ReceiverRestrictedContext extends ContextWrapper {
    ReceiverRestrictedContext(Context base) {
        super(base);
    }

    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        return registerReceiver(receiver, filter, null, null);
    }

    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
            String broadcastPermission, Handler scheduler) {
        throw new ReceiverCallNotAllowedException(
                "IntentReceiver components are not allowed to register to receive intents");
        //ex.fillInStackTrace();
        //Log.e("IntentReceiver", ex.getMessage(), ex);
        //return mContext.registerReceiver(receiver, filter, broadcastPermission,
        //        scheduler);
    }

    @Override
    public boolean bindService(Intent service, ServiceConnection conn, int flags) {
        throw new ReceiverCallNotAllowedException(
                "IntentReceiver components are not allowed to bind to services");
        //ex.fillInStackTrace();
        //Log.e("IntentReceiver", ex.getMessage(), ex);
        //return mContext.bindService(service, interfaceName, conn, flags);
    }
}

/**
 * Common implementation of Context API, which provides the base
 * context object for Activity and other application components.
 */
class ContextImpl extends Context {
    private final static String TAG = "ApplicationContext";
    private final static boolean DEBUG = false;
    private final static boolean DEBUG_ICONS = false;

    private static final Object sSync = new Object();
    private static AlarmManager sAlarmManager;
    private static PowerManager sPowerManager;
    private static ConnectivityManager sConnectivityManager;
    private static ThrottleManager sThrottleManager;
    private static WifiManager sWifiManager;
    private static LocationManager sLocationManager;
    private static final HashMap<File, SharedPreferencesImpl> sSharedPrefs =
            new HashMap<File, SharedPreferencesImpl>();

    private AudioManager mAudioManager;
    /*package*/ LoadedApk mPackageInfo;
    private Resources mResources;
    /*package*/ ActivityThread mMainThread;
    private Context mOuterContext;
    private IBinder mActivityToken = null;
    private ApplicationContentResolver mContentResolver;
    private int mThemeResource = 0;
    private Resources.Theme mTheme = null;
    private PackageManager mPackageManager;
    private NotificationManager mNotificationManager = null;
    private ActivityManager mActivityManager = null;
    private WallpaperManager mWallpaperManager = null;
    private Context mReceiverRestrictedContext = null;
    private SearchManager mSearchManager = null;
    private SensorManager mSensorManager = null;
    private StorageManager mStorageManager = null;
    private Vibrator mVibrator = null;
    private LayoutInflater mLayoutInflater = null;
    private StatusBarManager mStatusBarManager = null;
    private TelephonyManager mTelephonyManager = null;
    private ClipboardManager mClipboardManager = null;
    private boolean mRestricted;
    private AccountManager mAccountManager; // protected by mSync
    private DropBoxManager mDropBoxManager = null;
    private DevicePolicyManager mDevicePolicyManager = null;
    private UiModeManager mUiModeManager = null;

    private final Object mSync = new Object();

    private File mDatabasesDir;
    private File mPreferencesDir;
    private File mFilesDir;
    private File mCacheDir;
    private File mExternalFilesDir;
    private File mExternalCacheDir;

    private static long sInstanceCount = 0;

    private static final String[] EMPTY_FILE_LIST = {};

    // For debug only
    /*
    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        --sInstanceCount;
    }
    */

    public static long getInstanceCount() {
        return sInstanceCount;
    }

    @Override
    public AssetManager getAssets() {
        return mResources.getAssets();
    }

    @Override
    public Resources getResources() {
        return mResources;
    }

    @Override
    public PackageManager getPackageManager() {
        if (mPackageManager != null) {
            return mPackageManager;
        }

        IPackageManager pm = ActivityThread.getPackageManager();
        if (pm != null) {
            // Doesn't matter if we make more than one instance.
            return (mPackageManager = new ApplicationPackageManager(this, pm));
        }

        return null;
    }

    @Override
    public ContentResolver getContentResolver() {
        return mContentResolver;
    }

    @Override
    public Looper getMainLooper() {
        return mMainThread.getLooper();
    }

    @Override
    public Context getApplicationContext() {
        return (mPackageInfo != null) ?
                mPackageInfo.getApplication() : mMainThread.getApplication();
    }

    @Override
    public void setTheme(int resid) {
        mThemeResource = resid;
    }

    @Override
    public Resources.Theme getTheme() {
        if (mTheme == null) {
            if (mThemeResource == 0) {
                mThemeResource = com.android.internal.R.style.Theme;
            }
            mTheme = mResources.newTheme();
            mTheme.applyStyle(mThemeResource, true);
        }
        return mTheme;
    }

    @Override
    public ClassLoader getClassLoader() {
        return mPackageInfo != null ?
                mPackageInfo.getClassLoader() : ClassLoader.getSystemClassLoader();
    }

    @Override
    public String getPackageName() {
        if (mPackageInfo != null) {
            return mPackageInfo.getPackageName();
        }
        throw new RuntimeException("Not supported in system context");
    }

    @Override
    public ApplicationInfo getApplicationInfo() {
        if (mPackageInfo != null) {
            return mPackageInfo.getApplicationInfo();
        }
        throw new RuntimeException("Not supported in system context");
    }

    @Override
    public String getPackageResourcePath() {
        if (mPackageInfo != null) {
            return mPackageInfo.getResDir();
        }
        throw new RuntimeException("Not supported in system context");
    }

    @Override
    public String getPackageCodePath() {
        if (mPackageInfo != null) {
            return mPackageInfo.getAppDir();
        }
        throw new RuntimeException("Not supported in system context");
    }

    private static File makeBackupFile(File prefsFile) {
        return new File(prefsFile.getPath() + ".bak");
    }

    public File getSharedPrefsFile(String name) {
        return makeFilename(getPreferencesDir(), name + ".xml");
    }

    @Override
    public SharedPreferences getSharedPreferences(String name, int mode) {
        SharedPreferencesImpl sp;
        File f = getSharedPrefsFile(name);
        synchronized (sSharedPrefs) {
            sp = sSharedPrefs.get(f);
            if (sp != null && !sp.hasFileChanged()) {
                //Log.i(TAG, "Returning existing prefs " + name + ": " + sp);
                return sp;
            }
        }

        FileInputStream str = null;
        File backup = makeBackupFile(f);
        if (backup.exists()) {
            f.delete();
            backup.renameTo(f);
        }

        // Debugging
        if (f.exists() && !f.canRead()) {
            Log.w(TAG, "Attempt to read preferences file " + f + " without permission");
        }

        Map map = null;
        if (f.exists() && f.canRead()) {
            try {
                str = new FileInputStream(f);
                map = XmlUtils.readMapXml(str);
                str.close();
            } catch (org.xmlpull.v1.XmlPullParserException e) {
                Log.w(TAG, "getSharedPreferences", e);
            } catch (FileNotFoundException e) {
                Log.w(TAG, "getSharedPreferences", e);
            } catch (IOException e) {
                Log.w(TAG, "getSharedPreferences", e);
            }
        }

        synchronized (sSharedPrefs) {
            if (sp != null) {
                //Log.i(TAG, "Updating existing prefs " + name + " " + sp + ": " + map);
                sp.replace(map);
            } else {
                sp = sSharedPrefs.get(f);
                if (sp == null) {
                    sp = new SharedPreferencesImpl(f, mode, map);
                    sSharedPrefs.put(f, sp);
                }
            }
            return sp;
        }
    }

    private File getPreferencesDir() {
        synchronized (mSync) {
            if (mPreferencesDir == null) {
                mPreferencesDir = new File(getDataDirFile(), "shared_prefs");
            }
            return mPreferencesDir;
        }
    }

    @Override
    public FileInputStream openFileInput(String name)
        throws FileNotFoundException {
        File f = makeFilename(getFilesDir(), name);
        return new FileInputStream(f);
    }

    @Override
    public FileOutputStream openFileOutput(String name, int mode)
        throws FileNotFoundException {
        final boolean append = (mode&MODE_APPEND) != 0;
        File f = makeFilename(getFilesDir(), name);
        try {
            FileOutputStream fos = new FileOutputStream(f, append);
            setFilePermissionsFromMode(f.getPath(), mode, 0);
            return fos;
        } catch (FileNotFoundException e) {
        }

        File parent = f.getParentFile();
        parent.mkdir();
        FileUtils.setPermissions(
            parent.getPath(),
            FileUtils.S_IRWXU|FileUtils.S_IRWXG|FileUtils.S_IXOTH,
            -1, -1);
        FileOutputStream fos = new FileOutputStream(f, append);
        setFilePermissionsFromMode(f.getPath(), mode, 0);
        return fos;
    }

    @Override
    public boolean deleteFile(String name) {
        File f = makeFilename(getFilesDir(), name);
        return f.delete();
    }

    @Override
    public File getFilesDir() {
        synchronized (mSync) {
            if (mFilesDir == null) {
                mFilesDir = new File(getDataDirFile(), "files");
            }
            if (!mFilesDir.exists()) {
                if(!mFilesDir.mkdirs()) {
                    Log.w(TAG, "Unable to create files directory " + mFilesDir.getPath());
                    return null;
                }
                FileUtils.setPermissions(
                        mFilesDir.getPath(),
                        FileUtils.S_IRWXU|FileUtils.S_IRWXG|FileUtils.S_IXOTH,
                        -1, -1);
            }
            return mFilesDir;
        }
    }

    @Override
    public File getExternalFilesDir(String type) {
        synchronized (mSync) {
            if (mExternalFilesDir == null) {
                mExternalFilesDir = Environment.getExternalStorageAppFilesDirectory(
                        getPackageName());
            }
            if (!mExternalFilesDir.exists()) {
                try {
                    (new File(Environment.getExternalStorageAndroidDataDir(),
                            ".nomedia")).createNewFile();
                } catch (IOException e) {
                }
                if (!mExternalFilesDir.mkdirs()) {
                    Log.w(TAG, "Unable to create external files directory");
                    return null;
                }
            }
            if (type == null) {
                return mExternalFilesDir;
            }
            File dir = new File(mExternalFilesDir, type);
            if (!dir.exists()) {
                if (!dir.mkdirs()) {
                    Log.w(TAG, "Unable to create external media directory " + dir);
                    return null;
                }
            }
            return dir;
        }
    }

    @Override
    public File getCacheDir() {
        synchronized (mSync) {
            if (mCacheDir == null) {
                mCacheDir = new File(getDataDirFile(), "cache");
            }
            if (!mCacheDir.exists()) {
                if(!mCacheDir.mkdirs()) {
                    Log.w(TAG, "Unable to create cache directory");
                    return null;
                }
                FileUtils.setPermissions(
                        mCacheDir.getPath(),
                        FileUtils.S_IRWXU|FileUtils.S_IRWXG|FileUtils.S_IXOTH,
                        -1, -1);
            }
        }
        return mCacheDir;
    }

    @Override
    public File getExternalCacheDir() {
        synchronized (mSync) {
            if (mExternalCacheDir == null) {
                mExternalCacheDir = Environment.getExternalStorageAppCacheDirectory(
                        getPackageName());
            }
            if (!mExternalCacheDir.exists()) {
                try {
                    (new File(Environment.getExternalStorageAndroidDataDir(),
                            ".nomedia")).createNewFile();
                } catch (IOException e) {
                }
                if (!mExternalCacheDir.mkdirs()) {
                    Log.w(TAG, "Unable to create external cache directory");
                    return null;
                }
            }
            return mExternalCacheDir;
        }
    }

    @Override
    public File getFileStreamPath(String name) {
        return makeFilename(getFilesDir(), name);
    }

    @Override
    public String[] fileList() {
        final String[] list = getFilesDir().list();
        return (list != null) ? list : EMPTY_FILE_LIST;
    }

    @Override
    public SQLiteDatabase openOrCreateDatabase(String name, int mode, CursorFactory factory) {
        File f = validateFilePath(name, true);
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(f, factory);
        setFilePermissionsFromMode(f.getPath(), mode, 0);
        return db;
    }

    @Override
    public boolean deleteDatabase(String name) {
        try {
            File f = validateFilePath(name, false);
            return f.delete();
        } catch (Exception e) {
        }
        return false;
    }

    @Override
    public File getDatabasePath(String name) {
        return validateFilePath(name, false);
    }

    @Override
    public String[] databaseList() {
        final String[] list = getDatabasesDir().list();
        return (list != null) ? list : EMPTY_FILE_LIST;
    }


    private File getDatabasesDir() {
        synchronized (mSync) {
            if (mDatabasesDir == null) {
                mDatabasesDir = new File(getDataDirFile(), "databases");
            }
            if (mDatabasesDir.getPath().equals("databases")) {
                mDatabasesDir = new File("/data/system");
            }
            return mDatabasesDir;
        }
    }

    @Override
    public Drawable getWallpaper() {
        return getWallpaperManager().getDrawable();
    }

    @Override
    public Drawable peekWallpaper() {
        return getWallpaperManager().peekDrawable();
    }

    @Override
    public int getWallpaperDesiredMinimumWidth() {
        return getWallpaperManager().getDesiredMinimumWidth();
    }

    @Override
    public int getWallpaperDesiredMinimumHeight() {
        return getWallpaperManager().getDesiredMinimumHeight();
    }

    @Override
    public void setWallpaper(Bitmap bitmap) throws IOException  {
        getWallpaperManager().setBitmap(bitmap);
    }

    @Override
    public void setWallpaper(InputStream data) throws IOException {
        getWallpaperManager().setStream(data);
    }

    @Override
    public void clearWallpaper() throws IOException {
        getWallpaperManager().clear();
    }

    @Override
    public void startActivity(Intent intent) {
        if ((intent.getFlags()&Intent.FLAG_ACTIVITY_NEW_TASK) == 0) {
            throw new AndroidRuntimeException(
                    "Calling startActivity() from outside of an Activity "
                    + " context requires the FLAG_ACTIVITY_NEW_TASK flag."
                    + " Is this really what you want?");
        }
        mMainThread.getInstrumentation().execStartActivity(
            getOuterContext(), mMainThread.getApplicationThread(), null, null, intent, -1);
    }

    @Override
    public void startIntentSender(IntentSender intent,
            Intent fillInIntent, int flagsMask, int flagsValues, int extraFlags)
            throws IntentSender.SendIntentException {
        try {
            String resolvedType = null;
            if (fillInIntent != null) {
                resolvedType = fillInIntent.resolveTypeIfNeeded(getContentResolver());
            }
            int result = ActivityManagerNative.getDefault()
                .startActivityIntentSender(mMainThread.getApplicationThread(), intent,
                        fillInIntent, resolvedType, null, null,
                        0, flagsMask, flagsValues);
            if (result == IActivityManager.START_CANCELED) {
                throw new IntentSender.SendIntentException();
            }
            Instrumentation.checkStartActivityResult(result, null);
        } catch (RemoteException e) {
        }
    }

    @Override
    public void sendBroadcast(Intent intent) {
        String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
        try {
            ActivityManagerNative.getDefault().broadcastIntent(
                mMainThread.getApplicationThread(), intent, resolvedType, null,
                Activity.RESULT_OK, null, null, null, false, false);
        } catch (RemoteException e) {
        }
    }

    @Override
    public void sendBroadcast(Intent intent, String receiverPermission) {
        String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
        try {
            ActivityManagerNative.getDefault().broadcastIntent(
                mMainThread.getApplicationThread(), intent, resolvedType, null,
                Activity.RESULT_OK, null, null, receiverPermission, false, false);
        } catch (RemoteException e) {
        }
    }

    @Override
    public void sendOrderedBroadcast(Intent intent,
            String receiverPermission) {
        String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
        try {
            ActivityManagerNative.getDefault().broadcastIntent(
                mMainThread.getApplicationThread(), intent, resolvedType, null,
                Activity.RESULT_OK, null, null, receiverPermission, true, false);
        } catch (RemoteException e) {
        }
    }

    @Override
    public void sendOrderedBroadcast(Intent intent,
            String receiverPermission, BroadcastReceiver resultReceiver,
            Handler scheduler, int initialCode, String initialData,
            Bundle initialExtras) {
        IIntentReceiver rd = null;
        if (resultReceiver != null) {
            if (mPackageInfo != null) {
                if (scheduler == null) {
                    scheduler = mMainThread.getHandler();
                }
                rd = mPackageInfo.getReceiverDispatcher(
                    resultReceiver, getOuterContext(), scheduler,
                    mMainThread.getInstrumentation(), false);
            } else {
                if (scheduler == null) {
                    scheduler = mMainThread.getHandler();
                }
                rd = new LoadedApk.ReceiverDispatcher(
                        resultReceiver, getOuterContext(), scheduler, null, false).getIIntentReceiver();
            }
        }
        String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
        try {
            ActivityManagerNative.getDefault().broadcastIntent(
                mMainThread.getApplicationThread(), intent, resolvedType, rd,
                initialCode, initialData, initialExtras, receiverPermission,
                true, false);
        } catch (RemoteException e) {
        }
    }

    @Override
    public void sendStickyBroadcast(Intent intent) {
        String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
        try {
            ActivityManagerNative.getDefault().broadcastIntent(
                mMainThread.getApplicationThread(), intent, resolvedType, null,
                Activity.RESULT_OK, null, null, null, false, true);
        } catch (RemoteException e) {
        }
    }

    @Override
    public void sendStickyOrderedBroadcast(Intent intent,
            BroadcastReceiver resultReceiver,
            Handler scheduler, int initialCode, String initialData,
            Bundle initialExtras) {
        IIntentReceiver rd = null;
        if (resultReceiver != null) {
            if (mPackageInfo != null) {
                if (scheduler == null) {
                    scheduler = mMainThread.getHandler();
                }
                rd = mPackageInfo.getReceiverDispatcher(
                    resultReceiver, getOuterContext(), scheduler,
                    mMainThread.getInstrumentation(), false);
            } else {
                if (scheduler == null) {
                    scheduler = mMainThread.getHandler();
                }
                rd = new LoadedApk.ReceiverDispatcher(
                        resultReceiver, getOuterContext(), scheduler, null, false).getIIntentReceiver();
            }
        }
        String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
        try {
            ActivityManagerNative.getDefault().broadcastIntent(
                mMainThread.getApplicationThread(), intent, resolvedType, rd,
                initialCode, initialData, initialExtras, null,
                true, true);
        } catch (RemoteException e) {
        }
    }

    @Override
    public void removeStickyBroadcast(Intent intent) {
        String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
        if (resolvedType != null) {
            intent = new Intent(intent);
            intent.setDataAndType(intent.getData(), resolvedType);
        }
        try {
            ActivityManagerNative.getDefault().unbroadcastIntent(
                mMainThread.getApplicationThread(), intent);
        } catch (RemoteException e) {
        }
    }

    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        return registerReceiver(receiver, filter, null, null);
    }

    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
            String broadcastPermission, Handler scheduler) {
        return registerReceiverInternal(receiver, filter, broadcastPermission,
                scheduler, getOuterContext());
    }

    private Intent registerReceiverInternal(BroadcastReceiver receiver,
            IntentFilter filter, String broadcastPermission,
            Handler scheduler, Context context) {
        IIntentReceiver rd = null;
        if (receiver != null) {
            if (mPackageInfo != null && context != null) {
                if (scheduler == null) {
                    scheduler = mMainThread.getHandler();
                }
                rd = mPackageInfo.getReceiverDispatcher(
                    receiver, context, scheduler,
                    mMainThread.getInstrumentation(), true);
            } else {
                if (scheduler == null) {
                    scheduler = mMainThread.getHandler();
                }
                rd = new LoadedApk.ReceiverDispatcher(
                        receiver, context, scheduler, null, true).getIIntentReceiver();
            }
        }
        try {
            return ActivityManagerNative.getDefault().registerReceiver(
                    mMainThread.getApplicationThread(),
                    rd, filter, broadcastPermission);
        } catch (RemoteException e) {
            return null;
        }
    }

    @Override
    public void unregisterReceiver(BroadcastReceiver receiver) {
        if (mPackageInfo != null) {
            IIntentReceiver rd = mPackageInfo.forgetReceiverDispatcher(
                    getOuterContext(), receiver);
            try {
                ActivityManagerNative.getDefault().unregisterReceiver(rd);
            } catch (RemoteException e) {
            }
        } else {
            throw new RuntimeException("Not supported in system context");
        }
    }

    @Override
    public ComponentName startService(Intent service) {
        try {
            ComponentName cn = ActivityManagerNative.getDefault().startService(
                mMainThread.getApplicationThread(), service,
                service.resolveTypeIfNeeded(getContentResolver()));
            if (cn != null && cn.getPackageName().equals("!")) {
                throw new SecurityException(
                        "Not allowed to start service " + service
                        + " without permission " + cn.getClassName());
            }
            return cn;
        } catch (RemoteException e) {
            return null;
        }
    }

    @Override
    public boolean stopService(Intent service) {
        try {
            int res = ActivityManagerNative.getDefault().stopService(
                mMainThread.getApplicationThread(), service,
                service.resolveTypeIfNeeded(getContentResolver()));
            if (res < 0) {
                throw new SecurityException(
                        "Not allowed to stop service " + service);
            }
            return res != 0;
        } catch (RemoteException e) {
            return false;
        }
    }

    @Override
    public boolean bindService(Intent service, ServiceConnection conn,
            int flags) {
        IServiceConnection sd;
        if (mPackageInfo != null) {
            sd = mPackageInfo.getServiceDispatcher(conn, getOuterContext(),
                    mMainThread.getHandler(), flags);
        } else {
            throw new RuntimeException("Not supported in system context");
        }
        try {
            int res = ActivityManagerNative.getDefault().bindService(
                mMainThread.getApplicationThread(), getActivityToken(),
                service, service.resolveTypeIfNeeded(getContentResolver()),
                sd, flags);
            if (res < 0) {
                throw new SecurityException(
                        "Not allowed to bind to service " + service);
            }
            return res != 0;
        } catch (RemoteException e) {
            return false;
        }
    }

    @Override
    public void unbindService(ServiceConnection conn) {
        if (mPackageInfo != null) {
            IServiceConnection sd = mPackageInfo.forgetServiceDispatcher(
                    getOuterContext(), conn);
            try {
                ActivityManagerNative.getDefault().unbindService(sd);
            } catch (RemoteException e) {
            }
        } else {
            throw new RuntimeException("Not supported in system context");
        }
    }

    @Override
    public boolean startInstrumentation(ComponentName className,
            String profileFile, Bundle arguments) {
        try {
            return ActivityManagerNative.getDefault().startInstrumentation(
                    className, profileFile, 0, arguments, null);
        } catch (RemoteException e) {
            // System has crashed, nothing we can do.
        }
        return false;
    }

    @Override
    public Object getSystemService(String name) {
        if (WINDOW_SERVICE.equals(name)) {
            return WindowManagerImpl.getDefault();
        } else if (LAYOUT_INFLATER_SERVICE.equals(name)) {
            synchronized (mSync) {
                LayoutInflater inflater = mLayoutInflater;
                if (inflater != null) {
                    return inflater;
                }
                mLayoutInflater = inflater =
                    PolicyManager.makeNewLayoutInflater(getOuterContext());
                return inflater;
            }
        } else if (ACTIVITY_SERVICE.equals(name)) {
            return getActivityManager();
        } else if (INPUT_METHOD_SERVICE.equals(name)) {
            return InputMethodManager.getInstance(this);
        } else if (ALARM_SERVICE.equals(name)) {
            return getAlarmManager();
        } else if (ACCOUNT_SERVICE.equals(name)) {
            return getAccountManager();
        } else if (POWER_SERVICE.equals(name)) {
            return getPowerManager();
        } else if (CONNECTIVITY_SERVICE.equals(name)) {
            return getConnectivityManager();
        } else if (THROTTLE_SERVICE.equals(name)) {
            return getThrottleManager();
        } else if (WIFI_SERVICE.equals(name)) {
            return getWifiManager();
        } else if (NOTIFICATION_SERVICE.equals(name)) {
            return getNotificationManager();
        } else if (KEYGUARD_SERVICE.equals(name)) {
            return new KeyguardManager();
        } else if (ACCESSIBILITY_SERVICE.equals(name)) {
            return AccessibilityManager.getInstance(this);
        } else if (LOCATION_SERVICE.equals(name)) {
            return getLocationManager();
        } else if (SEARCH_SERVICE.equals(name)) {
            return getSearchManager();
        } else if (SENSOR_SERVICE.equals(name)) {
            return getSensorManager();
        } else if (STORAGE_SERVICE.equals(name)) {
            return getStorageManager();
        } else if (VIBRATOR_SERVICE.equals(name)) {
            return getVibrator();
        } else if (STATUS_BAR_SERVICE.equals(name)) {
            synchronized (mSync) {
                if (mStatusBarManager == null) {
                    mStatusBarManager = new StatusBarManager(getOuterContext());
                }
                return mStatusBarManager;
            }
        } else if (AUDIO_SERVICE.equals(name)) {
            return getAudioManager();
        } else if (TELEPHONY_SERVICE.equals(name)) {
            return getTelephonyManager();
        } else if (CLIPBOARD_SERVICE.equals(name)) {
            return getClipboardManager();
        } else if (WALLPAPER_SERVICE.equals(name)) {
            return getWallpaperManager();
        } else if (DROPBOX_SERVICE.equals(name)) {
            return getDropBoxManager();
        } else if (DEVICE_POLICY_SERVICE.equals(name)) {
            return getDevicePolicyManager();
        } else if (UI_MODE_SERVICE.equals(name)) {
            return getUiModeManager();
        }

        return null;
    }

    private AccountManager getAccountManager() {
        synchronized (mSync) {
            if (mAccountManager == null) {
                IBinder b = ServiceManager.getService(ACCOUNT_SERVICE);
                IAccountManager service = IAccountManager.Stub.asInterface(b);
                mAccountManager = new AccountManager(this, service);
            }
            return mAccountManager;
        }
    }

    private ActivityManager getActivityManager() {
        synchronized (mSync) {
            if (mActivityManager == null) {
                mActivityManager = new ActivityManager(getOuterContext(),
                        mMainThread.getHandler());
            }
        }
        return mActivityManager;
    }

    private AlarmManager getAlarmManager() {
        synchronized (sSync) {
            if (sAlarmManager == null) {
                IBinder b = ServiceManager.getService(ALARM_SERVICE);
                IAlarmManager service = IAlarmManager.Stub.asInterface(b);
                sAlarmManager = new AlarmManager(service);
            }
        }
        return sAlarmManager;
    }

    private PowerManager getPowerManager() {
        synchronized (sSync) {
            if (sPowerManager == null) {
                IBinder b = ServiceManager.getService(POWER_SERVICE);
                IPowerManager service = IPowerManager.Stub.asInterface(b);
                sPowerManager = new PowerManager(service, mMainThread.getHandler());
            }
        }
        return sPowerManager;
    }

    private ConnectivityManager getConnectivityManager()
    {
        synchronized (sSync) {
            if (sConnectivityManager == null) {
                IBinder b = ServiceManager.getService(CONNECTIVITY_SERVICE);
                IConnectivityManager service = IConnectivityManager.Stub.asInterface(b);
                sConnectivityManager = new ConnectivityManager(service);
            }
        }
        return sConnectivityManager;
    }

    private ThrottleManager getThrottleManager()
    {
        synchronized (sSync) {
            if (sThrottleManager == null) {
                IBinder b = ServiceManager.getService(THROTTLE_SERVICE);
                IThrottleManager service = IThrottleManager.Stub.asInterface(b);
                sThrottleManager = new ThrottleManager(service);
            }
        }
        return sThrottleManager;
    }

    private WifiManager getWifiManager()
    {
        synchronized (sSync) {
            if (sWifiManager == null) {
                IBinder b = ServiceManager.getService(WIFI_SERVICE);
                IWifiManager service = IWifiManager.Stub.asInterface(b);
                sWifiManager = new WifiManager(service, mMainThread.getHandler());
            }
        }
        return sWifiManager;
    }

    private NotificationManager getNotificationManager() {
        synchronized (mSync) {
            if (mNotificationManager == null) {
                mNotificationManager = new NotificationManager(
                        new ContextThemeWrapper(getOuterContext(), com.android.internal.R.style.Theme_Dialog),
                        mMainThread.getHandler());
            }
        }
        return mNotificationManager;
    }

    private WallpaperManager getWallpaperManager() {
        synchronized (mSync) {
            if (mWallpaperManager == null) {
                mWallpaperManager = new WallpaperManager(getOuterContext(),
                        mMainThread.getHandler());
            }
        }
        return mWallpaperManager;
    }

    private TelephonyManager getTelephonyManager() {
        synchronized (mSync) {
            if (mTelephonyManager == null) {
                mTelephonyManager = new TelephonyManager(getOuterContext());
            }
        }
        return mTelephonyManager;
    }

    private ClipboardManager getClipboardManager() {
        synchronized (mSync) {
            if (mClipboardManager == null) {
                mClipboardManager = new ClipboardManager(getOuterContext(),
                        mMainThread.getHandler());
            }
        }
        return mClipboardManager;
    }

    private LocationManager getLocationManager() {
        synchronized (sSync) {
            if (sLocationManager == null) {
                IBinder b = ServiceManager.getService(LOCATION_SERVICE);
                ILocationManager service = ILocationManager.Stub.asInterface(b);
                sLocationManager = new LocationManager(service);
            }
        }
        return sLocationManager;
    }

    private SearchManager getSearchManager() {
        synchronized (mSync) {
            if (mSearchManager == null) {
                mSearchManager = new SearchManager(getOuterContext(), mMainThread.getHandler());
            }
        }
        return mSearchManager;
    }

    private SensorManager getSensorManager() {
        synchronized (mSync) {
            if (mSensorManager == null) {
                mSensorManager = new SensorManager(mMainThread.getHandler().getLooper());
            }
        }
        return mSensorManager;
    }

    private StorageManager getStorageManager() {
        synchronized (mSync) {
            if (mStorageManager == null) {
                try {
                    mStorageManager = new StorageManager(mMainThread.getHandler().getLooper());
                } catch (RemoteException rex) {
                    Log.e(TAG, "Failed to create StorageManager", rex);
                    mStorageManager = null;
                }
            }
        }
        return mStorageManager;
    }

    private Vibrator getVibrator() {
        synchronized (mSync) {
            if (mVibrator == null) {
                mVibrator = new Vibrator();
            }
        }
        return mVibrator;
    }

    private AudioManager getAudioManager()
    {
        if (mAudioManager == null) {
            mAudioManager = new AudioManager(this);
        }
        return mAudioManager;
    }

    /* package */ static DropBoxManager createDropBoxManager() {
        IBinder b = ServiceManager.getService(DROPBOX_SERVICE);
        IDropBoxManagerService service = IDropBoxManagerService.Stub.asInterface(b);
        return new DropBoxManager(service);
    }

    private DropBoxManager getDropBoxManager() {
        synchronized (mSync) {
            if (mDropBoxManager == null) {
                mDropBoxManager = createDropBoxManager();
            }
        }
        return mDropBoxManager;
    }

    private DevicePolicyManager getDevicePolicyManager() {
        synchronized (mSync) {
            if (mDevicePolicyManager == null) {
                mDevicePolicyManager = DevicePolicyManager.create(this,
                        mMainThread.getHandler());
            }
        }
        return mDevicePolicyManager;
    }

    private UiModeManager getUiModeManager() {
        synchronized (mSync) {
            if (mUiModeManager == null) {
                mUiModeManager = new UiModeManager();
            }
        }
        return mUiModeManager;
    }

    @Override
    public int checkPermission(String permission, int pid, int uid) {
        if (permission == null) {
            throw new IllegalArgumentException("permission is null");
        }

        if (!Process.supportsProcesses()) {
            return PackageManager.PERMISSION_GRANTED;
        }
        try {
            return ActivityManagerNative.getDefault().checkPermission(
                    permission, pid, uid);
        } catch (RemoteException e) {
            return PackageManager.PERMISSION_DENIED;
        }
    }

    @Override
    public int checkCallingPermission(String permission) {
        if (permission == null) {
            throw new IllegalArgumentException("permission is null");
        }

        if (!Process.supportsProcesses()) {
            return PackageManager.PERMISSION_GRANTED;
        }
        int pid = Binder.getCallingPid();
        if (pid != Process.myPid()) {
            return checkPermission(permission, pid,
                    Binder.getCallingUid());
        }
        return PackageManager.PERMISSION_DENIED;
    }

    @Override
    public int checkCallingOrSelfPermission(String permission) {
        if (permission == null) {
            throw new IllegalArgumentException("permission is null");
        }

        return checkPermission(permission, Binder.getCallingPid(),
                Binder.getCallingUid());
    }

    private void enforce(
            String permission, int resultOfCheck,
            boolean selfToo, int uid, String message) {
        if (resultOfCheck != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(
                    (message != null ? (message + ": ") : "") +
                    (selfToo
                     ? "Neither user " + uid + " nor current process has "
                     : "User " + uid + " does not have ") +
                    permission +
                    ".");
        }
    }

    public void enforcePermission(
            String permission, int pid, int uid, String message) {
        enforce(permission,
                checkPermission(permission, pid, uid),
                false,
                uid,
                message);
    }

    public void enforceCallingPermission(String permission, String message) {
        enforce(permission,
                checkCallingPermission(permission),
                false,
                Binder.getCallingUid(),
                message);
    }

    public void enforceCallingOrSelfPermission(
            String permission, String message) {
        enforce(permission,
                checkCallingOrSelfPermission(permission),
                true,
                Binder.getCallingUid(),
                message);
    }

    @Override
    public void grantUriPermission(String toPackage, Uri uri, int modeFlags) {
         try {
            ActivityManagerNative.getDefault().grantUriPermission(
                    mMainThread.getApplicationThread(), toPackage, uri,
                    modeFlags);
        } catch (RemoteException e) {
        }
    }

    @Override
    public void revokeUriPermission(Uri uri, int modeFlags) {
         try {
            ActivityManagerNative.getDefault().revokeUriPermission(
                    mMainThread.getApplicationThread(), uri,
                    modeFlags);
        } catch (RemoteException e) {
        }
    }

    @Override
    public int checkUriPermission(Uri uri, int pid, int uid, int modeFlags) {
        if (!Process.supportsProcesses()) {
            return PackageManager.PERMISSION_GRANTED;
        }
        try {
            return ActivityManagerNative.getDefault().checkUriPermission(
                    uri, pid, uid, modeFlags);
        } catch (RemoteException e) {
            return PackageManager.PERMISSION_DENIED;
        }
    }

    @Override
    public int checkCallingUriPermission(Uri uri, int modeFlags) {
        if (!Process.supportsProcesses()) {
            return PackageManager.PERMISSION_GRANTED;
        }
        int pid = Binder.getCallingPid();
        if (pid != Process.myPid()) {
            return checkUriPermission(uri, pid,
                    Binder.getCallingUid(), modeFlags);
        }
        return PackageManager.PERMISSION_DENIED;
    }

    @Override
    public int checkCallingOrSelfUriPermission(Uri uri, int modeFlags) {
        return checkUriPermission(uri, Binder.getCallingPid(),
                Binder.getCallingUid(), modeFlags);
    }

    @Override
    public int checkUriPermission(Uri uri, String readPermission,
            String writePermission, int pid, int uid, int modeFlags) {
        if (DEBUG) {
            Log.i("foo", "checkUriPermission: uri=" + uri + "readPermission="
                    + readPermission + " writePermission=" + writePermission
                    + " pid=" + pid + " uid=" + uid + " mode" + modeFlags);
        }
        if ((modeFlags&Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
            if (readPermission == null
                    || checkPermission(readPermission, pid, uid)
                    == PackageManager.PERMISSION_GRANTED) {
                return PackageManager.PERMISSION_GRANTED;
            }
        }
        if ((modeFlags&Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) {
            if (writePermission == null
                    || checkPermission(writePermission, pid, uid)
                    == PackageManager.PERMISSION_GRANTED) {
                return PackageManager.PERMISSION_GRANTED;
            }
        }
        return uri != null ? checkUriPermission(uri, pid, uid, modeFlags)
                : PackageManager.PERMISSION_DENIED;
    }

    private String uriModeFlagToString(int uriModeFlags) {
        switch (uriModeFlags) {
            case Intent.FLAG_GRANT_READ_URI_PERMISSION |
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION:
                return "read and write";
            case Intent.FLAG_GRANT_READ_URI_PERMISSION:
                return "read";
            case Intent.FLAG_GRANT_WRITE_URI_PERMISSION:
                return "write";
        }
        throw new IllegalArgumentException(
                "Unknown permission mode flags: " + uriModeFlags);
    }

    private void enforceForUri(
            int modeFlags, int resultOfCheck, boolean selfToo,
            int uid, Uri uri, String message) {
        if (resultOfCheck != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(
                    (message != null ? (message + ": ") : "") +
                    (selfToo
                     ? "Neither user " + uid + " nor current process has "
                     : "User " + uid + " does not have ") +
                    uriModeFlagToString(modeFlags) +
                    " permission on " +
                    uri +
                    ".");
        }
    }

    public void enforceUriPermission(
            Uri uri, int pid, int uid, int modeFlags, String message) {
        enforceForUri(
                modeFlags, checkUriPermission(uri, pid, uid, modeFlags),
                false, uid, uri, message);
    }

    public void enforceCallingUriPermission(
            Uri uri, int modeFlags, String message) {
        enforceForUri(
                modeFlags, checkCallingUriPermission(uri, modeFlags),
                false, Binder.getCallingUid(), uri, message);
    }

    public void enforceCallingOrSelfUriPermission(
            Uri uri, int modeFlags, String message) {
        enforceForUri(
                modeFlags,
                checkCallingOrSelfUriPermission(uri, modeFlags), true,
                Binder.getCallingUid(), uri, message);
    }

    public void enforceUriPermission(
            Uri uri, String readPermission, String writePermission,
            int pid, int uid, int modeFlags, String message) {
        enforceForUri(modeFlags,
                      checkUriPermission(
                              uri, readPermission, writePermission, pid, uid,
                              modeFlags),
                      false,
                      uid,
                      uri,
                      message);
    }

    @Override
    public Context createPackageContext(String packageName, int flags)
        throws PackageManager.NameNotFoundException {
        if (packageName.equals("system") || packageName.equals("android")) {
            return new ContextImpl(mMainThread.getSystemContext());
        }

        LoadedApk pi =
            mMainThread.getPackageInfo(packageName, flags);
        if (pi != null) {
            ContextImpl c = new ContextImpl();
            c.mRestricted = (flags & CONTEXT_RESTRICTED) == CONTEXT_RESTRICTED;
            c.init(pi, null, mMainThread, mResources);
            if (c.mResources != null) {
                return c;
            }
        }

        // Should be a better exception.
        throw new PackageManager.NameNotFoundException(
            "Application package " + packageName + " not found");
    }

    @Override
    public boolean isRestricted() {
        return mRestricted;
    }

    private File getDataDirFile() {
        if (mPackageInfo != null) {
            return mPackageInfo.getDataDirFile();
        }
        throw new RuntimeException("Not supported in system context");
    }

    @Override
    public File getDir(String name, int mode) {
        name = "app_" + name;
        File file = makeFilename(getDataDirFile(), name);
        if (!file.exists()) {
            file.mkdir();
            setFilePermissionsFromMode(file.getPath(), mode,
                    FileUtils.S_IRWXU|FileUtils.S_IRWXG|FileUtils.S_IXOTH);
        }
        return file;
    }

    static ContextImpl createSystemContext(ActivityThread mainThread) {
        ContextImpl context = new ContextImpl();
        context.init(Resources.getSystem(), mainThread);
        return context;
    }

    ContextImpl() {
        // For debug only
        //++sInstanceCount;
        mOuterContext = this;
    }

    /**
     * Create a new ApplicationContext from an existing one.  The new one
     * works and operates the same as the one it is copying.
     *
     * @param context Existing application context.
     */
    public ContextImpl(ContextImpl context) {
        ++sInstanceCount;
        mPackageInfo = context.mPackageInfo;
        mResources = context.mResources;
        mMainThread = context.mMainThread;
        mContentResolver = context.mContentResolver;
        mOuterContext = this;
    }

    final void init(LoadedApk packageInfo,
            IBinder activityToken, ActivityThread mainThread) {
        init(packageInfo, activityToken, mainThread, null);
    }

    final void init(LoadedApk packageInfo,
                IBinder activityToken, ActivityThread mainThread,
                Resources container) {
        mPackageInfo = packageInfo;
        mResources = mPackageInfo.getResources(mainThread);

        if (mResources != null && container != null
                && container.getCompatibilityInfo().applicationScale !=
                        mResources.getCompatibilityInfo().applicationScale) {
            if (DEBUG) {
                Log.d(TAG, "loaded context has different scaling. Using container's" +
                        " compatiblity info:" + container.getDisplayMetrics());
            }
            mResources = mainThread.getTopLevelResources(
                    mPackageInfo.getResDir(), container.getCompatibilityInfo().copy());
        }
        mMainThread = mainThread;
        mContentResolver = new ApplicationContentResolver(this, mainThread);

        setActivityToken(activityToken);
    }

    final void init(Resources resources, ActivityThread mainThread) {
        mPackageInfo = null;
        mResources = resources;
        mMainThread = mainThread;
        mContentResolver = new ApplicationContentResolver(this, mainThread);
    }

    final void scheduleFinalCleanup(String who, String what) {
        mMainThread.scheduleContextCleanup(this, who, what);
    }

    final void performFinalCleanup(String who, String what) {
        //Log.i(TAG, "Cleanup up context: " + this);
        mPackageInfo.removeContextRegistrations(getOuterContext(), who, what);
    }

    final Context getReceiverRestrictedContext() {
        if (mReceiverRestrictedContext != null) {
            return mReceiverRestrictedContext;
        }
        return mReceiverRestrictedContext = new ReceiverRestrictedContext(getOuterContext());
    }

    final void setActivityToken(IBinder token) {
        mActivityToken = token;
    }

    final void setOuterContext(Context context) {
        mOuterContext = context;
    }

    final Context getOuterContext() {
        return mOuterContext;
    }

    final IBinder getActivityToken() {
        return mActivityToken;
    }

    private static void setFilePermissionsFromMode(String name, int mode,
            int extraPermissions) {
        int perms = FileUtils.S_IRUSR|FileUtils.S_IWUSR
            |FileUtils.S_IRGRP|FileUtils.S_IWGRP
            |extraPermissions;
        if ((mode&MODE_WORLD_READABLE) != 0) {
            perms |= FileUtils.S_IROTH;
        }
        if ((mode&MODE_WORLD_WRITEABLE) != 0) {
            perms |= FileUtils.S_IWOTH;
        }
        if (DEBUG) {
            Log.i(TAG, "File " + name + ": mode=0x" + Integer.toHexString(mode)
                  + ", perms=0x" + Integer.toHexString(perms));
        }
        FileUtils.setPermissions(name, perms, -1, -1);
    }

    private File validateFilePath(String name, boolean createDirectory) {
        File dir;
        File f;

        if (name.charAt(0) == File.separatorChar) {
            String dirPath = name.substring(0, name.lastIndexOf(File.separatorChar));
            dir = new File(dirPath);
            name = name.substring(name.lastIndexOf(File.separatorChar));
            f = new File(dir, name);
        } else {
            dir = getDatabasesDir();
            f = makeFilename(dir, name);
        }

        if (createDirectory && !dir.isDirectory() && dir.mkdir()) {
            FileUtils.setPermissions(dir.getPath(),
                FileUtils.S_IRWXU|FileUtils.S_IRWXG|FileUtils.S_IXOTH,
                -1, -1);
        }

        return f;
    }

    private File makeFilename(File base, String name) {
        if (name.indexOf(File.separatorChar) < 0) {
            return new File(base, name);
        }
        throw new IllegalArgumentException(
                "File " + name + " contains a path separator");
    }

    // ----------------------------------------------------------------------
    // ----------------------------------------------------------------------
    // ----------------------------------------------------------------------

    private static final class ApplicationContentResolver extends ContentResolver {
        public ApplicationContentResolver(Context context,
                                          ActivityThread mainThread)
        {
            super(context);
            mMainThread = mainThread;
        }

        @Override
        protected IContentProvider acquireProvider(Context context, String name)
        {
            return mMainThread.acquireProvider(context, name);
        }

        @Override
        public boolean releaseProvider(IContentProvider provider)
        {
            return mMainThread.releaseProvider(provider);
        }

        private final ActivityThread mMainThread;
    }

    // ----------------------------------------------------------------------
    // ----------------------------------------------------------------------
    // ----------------------------------------------------------------------

    /*package*/
    static final class ApplicationPackageManager extends PackageManager {
        @Override
        public PackageInfo getPackageInfo(String packageName, int flags)
                throws NameNotFoundException {
            try {
                PackageInfo pi = mPM.getPackageInfo(packageName, flags);
                if (pi != null) {
                    return pi;
                }
            } catch (RemoteException e) {
                throw new RuntimeException("Package manager has died", e);
            }

            throw new NameNotFoundException(packageName);
        }

        @Override
        public String[] currentToCanonicalPackageNames(String[] names) {
            try {
                return mPM.currentToCanonicalPackageNames(names);
            } catch (RemoteException e) {
                throw new RuntimeException("Package manager has died", e);
            }
        }

        @Override
        public String[] canonicalToCurrentPackageNames(String[] names) {
            try {
                return mPM.canonicalToCurrentPackageNames(names);
            } catch (RemoteException e) {
                throw new RuntimeException("Package manager has died", e);
            }
        }

        @Override
        public Intent getLaunchIntentForPackage(String packageName) {
            // First see if the package has an INFO activity; the existence of
            // such an activity is implied to be the desired front-door for the
            // overall package (such as if it has multiple launcher entries).
            Intent intentToResolve = new Intent(Intent.ACTION_MAIN);
            intentToResolve.addCategory(Intent.CATEGORY_INFO);
            intentToResolve.setPackage(packageName);
            ResolveInfo resolveInfo = resolveActivity(intentToResolve, 0);

            // Otherwise, try to find a main launcher activity.
            if (resolveInfo == null) {
                // reuse the intent instance
                intentToResolve.removeCategory(Intent.CATEGORY_INFO);
                intentToResolve.addCategory(Intent.CATEGORY_LAUNCHER);
                intentToResolve.setPackage(packageName);
                resolveInfo = resolveActivity(intentToResolve, 0);
            }
            if (resolveInfo == null) {
                return null;
            }
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.setClassName(packageName, resolveInfo.activityInfo.name);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return intent;
        }

        @Override
        public int[] getPackageGids(String packageName)
            throws NameNotFoundException {
            try {
                int[] gids = mPM.getPackageGids(packageName);
                if (gids == null || gids.length > 0) {
                    return gids;
                }
            } catch (RemoteException e) {
                throw new RuntimeException("Package manager has died", e);
            }

            throw new NameNotFoundException(packageName);
        }

        @Override
        public PermissionInfo getPermissionInfo(String name, int flags)
            throws NameNotFoundException {
            try {
                PermissionInfo pi = mPM.getPermissionInfo(name, flags);
                if (pi != null) {
                    return pi;
                }
            } catch (RemoteException e) {
                throw new RuntimeException("Package manager has died", e);
            }

            throw new NameNotFoundException(name);
        }

        @Override
        public List<PermissionInfo> queryPermissionsByGroup(String group, int flags)
                throws NameNotFoundException {
            try {
                List<PermissionInfo> pi = mPM.queryPermissionsByGroup(group, flags);
                if (pi != null) {
                    return pi;
                }
            } catch (RemoteException e) {
                throw new RuntimeException("Package manager has died", e);
            }

            throw new NameNotFoundException(group);
        }

        @Override
        public PermissionGroupInfo getPermissionGroupInfo(String name,
                int flags) throws NameNotFoundException {
            try {
                PermissionGroupInfo pgi = mPM.getPermissionGroupInfo(name, flags);
                if (pgi != null) {
                    return pgi;
                }
            } catch (RemoteException e) {
                throw new RuntimeException("Package manager has died", e);
            }

            throw new NameNotFoundException(name);
        }

        @Override
        public List<PermissionGroupInfo> getAllPermissionGroups(int flags) {
            try {
                return mPM.getAllPermissionGroups(flags);
            } catch (RemoteException e) {
                throw new RuntimeException("Package manager has died", e);
            }
        }

        @Override
        public ApplicationInfo getApplicationInfo(String packageName, int flags)
            throws NameNotFoundException {
            try {
                ApplicationInfo ai = mPM.getApplicationInfo(packageName, flags);
                if (ai != null) {
                    return ai;
                }
            } catch (RemoteException e) {
                throw new RuntimeException("Package manager has died", e);
            }

            throw new NameNotFoundException(packageName);
        }

        @Override
        public ActivityInfo getActivityInfo(ComponentName className, int flags)
            throws NameNotFoundException {
            try {
                ActivityInfo ai = mPM.getActivityInfo(className, flags);
                if (ai != null) {
                    return ai;
                }
            } catch (RemoteException e) {
                throw new RuntimeException("Package manager has died", e);
            }

            throw new NameNotFoundException(className.toString());
        }

        @Override
        public ActivityInfo getReceiverInfo(ComponentName className, int flags)
            throws NameNotFoundException {
            try {
                ActivityInfo ai = mPM.getReceiverInfo(className, flags);
                if (ai != null) {
                    return ai;
                }
            } catch (RemoteException e) {
                throw new RuntimeException("Package manager has died", e);
            }

            throw new NameNotFoundException(className.toString());
        }

        @Override
        public ServiceInfo getServiceInfo(ComponentName className, int flags)
            throws NameNotFoundException {
            try {
                ServiceInfo si = mPM.getServiceInfo(className, flags);
                if (si != null) {
                    return si;
                }
            } catch (RemoteException e) {
                throw new RuntimeException("Package manager has died", e);
            }

            throw new NameNotFoundException(className.toString());
        }

        @Override
        public String[] getSystemSharedLibraryNames() {
             try {
                 return mPM.getSystemSharedLibraryNames();
             } catch (RemoteException e) {
                 throw new RuntimeException("Package manager has died", e);
             }
        }

        @Override
        public FeatureInfo[] getSystemAvailableFeatures() {
            try {
                return mPM.getSystemAvailableFeatures();
            } catch (RemoteException e) {
                throw new RuntimeException("Package manager has died", e);
            }
        }

        @Override
        public boolean hasSystemFeature(String name) {
            try {
                return mPM.hasSystemFeature(name);
            } catch (RemoteException e) {
                throw new RuntimeException("Package manager has died", e);
            }
        }

        @Override
        public int checkPermission(String permName, String pkgName) {
            try {
                return mPM.checkPermission(permName, pkgName);
            } catch (RemoteException e) {
                throw new RuntimeException("Package manager has died", e);
            }
        }

        @Override
        public boolean addPermission(PermissionInfo info) {
            try {
                return mPM.addPermission(info);
            } catch (RemoteException e) {
                throw new RuntimeException("Package manager has died", e);
            }
        }

        @Override
        public boolean addPermissionAsync(PermissionInfo info) {
            try {
                return mPM.addPermissionAsync(info);
            } catch (RemoteException e) {
                throw new RuntimeException("Package manager has died", e);
            }
        }

        @Override
        public void removePermission(String name) {
            try {
                mPM.removePermission(name);
            } catch (RemoteException e) {
                throw new RuntimeException("Package manager has died", e);
            }
        }

        @Override
        public int checkSignatures(String pkg1, String pkg2) {
            try {
                return mPM.checkSignatures(pkg1, pkg2);
            } catch (RemoteException e) {
                throw new RuntimeException("Package manager has died", e);
            }
        }

        @Override
        public int checkSignatures(int uid1, int uid2) {
            try {
                return mPM.checkUidSignatures(uid1, uid2);
            } catch (RemoteException e) {
                throw new RuntimeException("Package manager has died", e);
            }
        }

        @Override
        public String[] getPackagesForUid(int uid) {
            try {
                return mPM.getPackagesForUid(uid);
            } catch (RemoteException e) {
                throw new RuntimeException("Package manager has died", e);
            }
        }

        @Override
        public String getNameForUid(int uid) {
            try {
                return mPM.getNameForUid(uid);
            } catch (RemoteException e) {
                throw new RuntimeException("Package manager has died", e);
            }
        }

        @Override
        public int getUidForSharedUser(String sharedUserName)
                throws NameNotFoundException {
            try {
                int uid = mPM.getUidForSharedUser(sharedUserName);
                if(uid != -1) {
                    return uid;
                }
            } catch (RemoteException e) {
                throw new RuntimeException("Package manager has died", e);
            }
            throw new NameNotFoundException("No shared userid for user:"+sharedUserName);
        }

        @Override
        public List<PackageInfo> getInstalledPackages(int flags) {
            try {
                return mPM.getInstalledPackages(flags);
            } catch (RemoteException e) {
                throw new RuntimeException("Package manager has died", e);
            }
        }

        @Override
        public List<ApplicationInfo> getInstalledApplications(int flags) {
            try {
                return mPM.getInstalledApplications(flags);
            } catch (RemoteException e) {
                throw new RuntimeException("Package manager has died", e);
            }
        }

        @Override
        public ResolveInfo resolveActivity(Intent intent, int flags) {
            try {
                return mPM.resolveIntent(
                    intent,
                    intent.resolveTypeIfNeeded(mContext.getContentResolver()),
                    flags);
            } catch (RemoteException e) {
                throw new RuntimeException("Package manager has died", e);
            }
        }

        @Override
        public List<ResolveInfo> queryIntentActivities(Intent intent,
                int flags) {
            try {
                return mPM.queryIntentActivities(
                    intent,
                    intent.resolveTypeIfNeeded(mContext.getContentResolver()),
                    flags);
            } catch (RemoteException e) {
                throw new RuntimeException("Package manager has died", e);
            }
        }

        @Override
        public List<ResolveInfo> queryIntentActivityOptions(
                ComponentName caller, Intent[] specifics, Intent intent,
                int flags) {
            final ContentResolver resolver = mContext.getContentResolver();

            String[] specificTypes = null;
            if (specifics != null) {
                final int N = specifics.length;
                for (int i=0; i<N; i++) {
                    Intent sp = specifics[i];
                    if (sp != null) {
                        String t = sp.resolveTypeIfNeeded(resolver);
                        if (t != null) {
                            if (specificTypes == null) {
                                specificTypes = new String[N];
                            }
                            specificTypes[i] = t;
                        }
                    }
                }
            }

            try {
                return mPM.queryIntentActivityOptions(caller, specifics,
                    specificTypes, intent, intent.resolveTypeIfNeeded(resolver),
                    flags);
            } catch (RemoteException e) {
                throw new RuntimeException("Package manager has died", e);
            }
        }

        @Override
        public List<ResolveInfo> queryBroadcastReceivers(Intent intent, int flags) {
            try {
                return mPM.queryIntentReceivers(
                    intent,
                    intent.resolveTypeIfNeeded(mContext.getContentResolver()),
                    flags);
            } catch (RemoteException e) {
                throw new RuntimeException("Package manager has died", e);
            }
        }

        @Override
        public ResolveInfo resolveService(Intent intent, int flags) {
            try {
                return mPM.resolveService(
                    intent,
                    intent.resolveTypeIfNeeded(mContext.getContentResolver()),
                    flags);
            } catch (RemoteException e) {
                throw new RuntimeException("Package manager has died", e);
            }
        }

        @Override
        public List<ResolveInfo> queryIntentServices(Intent intent, int flags) {
            try {
                return mPM.queryIntentServices(
                    intent,
                    intent.resolveTypeIfNeeded(mContext.getContentResolver()),
                    flags);
            } catch (RemoteException e) {
                throw new RuntimeException("Package manager has died", e);
            }
        }

        @Override
        public ProviderInfo resolveContentProvider(String name,
                int flags) {
            try {
                return mPM.resolveContentProvider(name, flags);
            } catch (RemoteException e) {
                throw new RuntimeException("Package manager has died", e);
            }
        }

        @Override
        public List<ProviderInfo> queryContentProviders(String processName,
                int uid, int flags) {
            try {
                return mPM.queryContentProviders(processName, uid, flags);
            } catch (RemoteException e) {
                throw new RuntimeException("Package manager has died", e);
            }
        }

        @Override
        public InstrumentationInfo getInstrumentationInfo(
                ComponentName className, int flags)
                throws NameNotFoundException {
            try {
                InstrumentationInfo ii = mPM.getInstrumentationInfo(
                        className, flags);
                if (ii != null) {
                    return ii;
                }
            } catch (RemoteException e) {
                throw new RuntimeException("Package manager has died", e);
            }

            throw new NameNotFoundException(className.toString());
        }

        @Override
        public List<InstrumentationInfo> queryInstrumentation(
                String targetPackage, int flags) {
            try {
                return mPM.queryInstrumentation(targetPackage, flags);
            } catch (RemoteException e) {
                throw new RuntimeException("Package manager has died", e);
            }
        }

        @Override public Drawable getDrawable(String packageName, int resid,
                ApplicationInfo appInfo) {
            ResourceName name = new ResourceName(packageName, resid);
            Drawable dr = getCachedIcon(name);
            if (dr != null) {
                return dr;
            }
            if (appInfo == null) {
                try {
                    appInfo = getApplicationInfo(packageName, 0);
                } catch (NameNotFoundException e) {
                    return null;
                }
            }
            try {
                Resources r = getResourcesForApplication(appInfo);
                dr = r.getDrawable(resid);
                if (false) {
                    RuntimeException e = new RuntimeException("here");
                    e.fillInStackTrace();
                    Log.w(TAG, "Getting drawable 0x" + Integer.toHexString(resid)
                            + " from package " + packageName
                            + ": app scale=" + r.getCompatibilityInfo().applicationScale
                            + ", caller scale=" + mContext.getResources().getCompatibilityInfo().applicationScale,
                            e);
                }
                if (DEBUG_ICONS) Log.v(TAG, "Getting drawable 0x"
                        + Integer.toHexString(resid) + " from " + r
                        + ": " + dr);
                putCachedIcon(name, dr);
                return dr;
            } catch (NameNotFoundException e) {
                Log.w("PackageManager", "Failure retrieving resources for"
                        + appInfo.packageName);
            } catch (RuntimeException e) {
                // If an exception was thrown, fall through to return
                // default icon.
                Log.w("PackageManager", "Failure retrieving icon 0x"
                        + Integer.toHexString(resid) + " in package "
                        + packageName, e);
            }
            return null;
        }

        @Override public Drawable getActivityIcon(ComponentName activityName)
                throws NameNotFoundException {
            return getActivityInfo(activityName, 0).loadIcon(this);
        }

        @Override public Drawable getActivityIcon(Intent intent)
                throws NameNotFoundException {
            if (intent.getComponent() != null) {
                return getActivityIcon(intent.getComponent());
            }

            ResolveInfo info = resolveActivity(
                intent, PackageManager.MATCH_DEFAULT_ONLY);
            if (info != null) {
                return info.activityInfo.loadIcon(this);
            }

            throw new NameNotFoundException(intent.toURI());
        }

        @Override public Drawable getDefaultActivityIcon() {
            return Resources.getSystem().getDrawable(
                com.android.internal.R.drawable.sym_def_app_icon);
        }

        @Override public Drawable getApplicationIcon(ApplicationInfo info) {
            return info.loadIcon(this);
        }

        @Override public Drawable getApplicationIcon(String packageName)
                throws NameNotFoundException {
            return getApplicationIcon(getApplicationInfo(packageName, 0));
        }
        
        @Override 
        public Drawable getActivityLogo(ComponentName activityName)
                throws NameNotFoundException {
            return getActivityInfo(activityName, 0).loadLogo(this);
        }

        @Override
        public Drawable getActivityLogo(Intent intent)
                throws NameNotFoundException {
            if (intent.getComponent() != null) {
                return getActivityLogo(intent.getComponent());
            }

            ResolveInfo info = resolveActivity(
                    intent, PackageManager.MATCH_DEFAULT_ONLY);
            if (info != null) {
                return info.activityInfo.loadLogo(this);
            }

            throw new NameNotFoundException(intent.toUri(0));
        }

        @Override
        public Drawable getApplicationLogo(ApplicationInfo info) {
            return info.loadLogo(this);
        }

        @Override
        public Drawable getApplicationLogo(String packageName)
                throws NameNotFoundException {
            return getApplicationLogo(getApplicationInfo(packageName, 0));
        }

        @Override public Resources getResourcesForActivity(
                ComponentName activityName) throws NameNotFoundException {
            return getResourcesForApplication(
                getActivityInfo(activityName, 0).applicationInfo);
        }

        @Override public Resources getResourcesForApplication(
                ApplicationInfo app) throws NameNotFoundException {
            if (app.packageName.equals("system")) {
                return mContext.mMainThread.getSystemContext().getResources();
            }
            Resources r = mContext.mMainThread.getTopLevelResources(
                    app.uid == Process.myUid() ? app.sourceDir
                    : app.publicSourceDir, mContext.mPackageInfo);
            if (r != null) {
                return r;
            }
            throw new NameNotFoundException("Unable to open " + app.publicSourceDir);
        }

        @Override public Resources getResourcesForApplication(
                String appPackageName) throws NameNotFoundException {
            return getResourcesForApplication(
                getApplicationInfo(appPackageName, 0));
        }

        int mCachedSafeMode = -1;
        @Override public boolean isSafeMode() {
            try {
                if (mCachedSafeMode < 0) {
                    mCachedSafeMode = mPM.isSafeMode() ? 1 : 0;
                }
                return mCachedSafeMode != 0;
            } catch (RemoteException e) {
                throw new RuntimeException("Package manager has died", e);
            }
        }

        static void configurationChanged() {
            synchronized (sSync) {
                sIconCache.clear();
                sStringCache.clear();
            }
        }

        ApplicationPackageManager(ContextImpl context,
                IPackageManager pm) {
            mContext = context;
            mPM = pm;
        }

        private Drawable getCachedIcon(ResourceName name) {
            synchronized (sSync) {
                WeakReference<Drawable> wr = sIconCache.get(name);
                if (DEBUG_ICONS) Log.v(TAG, "Get cached weak drawable ref for "
                        + name + ": " + wr);
                if (wr != null) {   // we have the activity
                    Drawable dr = wr.get();
                    if (dr != null) {
                        if (DEBUG_ICONS) Log.v(TAG, "Get cached drawable for "
                                + name + ": " + dr);
                        return dr;
                    }
                    // our entry has been purged
                    sIconCache.remove(name);
                }
            }
            return null;
        }

        private void putCachedIcon(ResourceName name, Drawable dr) {
            synchronized (sSync) {
                sIconCache.put(name, new WeakReference<Drawable>(dr));
                if (DEBUG_ICONS) Log.v(TAG, "Added cached drawable for "
                        + name + ": " + dr);
            }
        }

        static final void handlePackageBroadcast(int cmd, String[] pkgList,
                boolean hasPkgInfo) {
            boolean immediateGc = false;
            if (cmd == IApplicationThread.EXTERNAL_STORAGE_UNAVAILABLE) {
                immediateGc = true;
            }
            if (pkgList != null && (pkgList.length > 0)) {
                boolean needCleanup = false;
                for (String ssp : pkgList) {
                    synchronized (sSync) {
                        if (sIconCache.size() > 0) {
                            Iterator<ResourceName> it = sIconCache.keySet().iterator();
                            while (it.hasNext()) {
                                ResourceName nm = it.next();
                                if (nm.packageName.equals(ssp)) {
                                    //Log.i(TAG, "Removing cached drawable for " + nm);
                                    it.remove();
                                    needCleanup = true;
                                }
                            }
                        }
                        if (sStringCache.size() > 0) {
                            Iterator<ResourceName> it = sStringCache.keySet().iterator();
                            while (it.hasNext()) {
                                ResourceName nm = it.next();
                                if (nm.packageName.equals(ssp)) {
                                    //Log.i(TAG, "Removing cached string for " + nm);
                                    it.remove();
                                    needCleanup = true;
                                }
                            }
                        }
                    }
                }
                if (needCleanup || hasPkgInfo) {
                    if (immediateGc) {
                        // Schedule an immediate gc.
                        Runtime.getRuntime().gc();
                    } else {
                        ActivityThread.currentActivityThread().scheduleGcIdler();
                    }
                }
            }
        }

        private static final class ResourceName {
            final String packageName;
            final int iconId;

            ResourceName(String _packageName, int _iconId) {
                packageName = _packageName;
                iconId = _iconId;
            }

            ResourceName(ApplicationInfo aInfo, int _iconId) {
                this(aInfo.packageName, _iconId);
            }

            ResourceName(ComponentInfo cInfo, int _iconId) {
                this(cInfo.applicationInfo.packageName, _iconId);
            }

            ResourceName(ResolveInfo rInfo, int _iconId) {
                this(rInfo.activityInfo.applicationInfo.packageName, _iconId);
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;

                ResourceName that = (ResourceName) o;

                if (iconId != that.iconId) return false;
                return !(packageName != null ?
                        !packageName.equals(that.packageName) : that.packageName != null);

            }

            @Override
            public int hashCode() {
                int result;
                result = packageName.hashCode();
                result = 31 * result + iconId;
                return result;
            }

            @Override
            public String toString() {
                return "{ResourceName " + packageName + " / " + iconId + "}";
            }
        }

        private CharSequence getCachedString(ResourceName name) {
            synchronized (sSync) {
                WeakReference<CharSequence> wr = sStringCache.get(name);
                if (wr != null) {   // we have the activity
                    CharSequence cs = wr.get();
                    if (cs != null) {
                        return cs;
                    }
                    // our entry has been purged
                    sStringCache.remove(name);
                }
            }
            return null;
        }

        private void putCachedString(ResourceName name, CharSequence cs) {
            synchronized (sSync) {
                sStringCache.put(name, new WeakReference<CharSequence>(cs));
            }
        }

        @Override
        public CharSequence getText(String packageName, int resid,
                ApplicationInfo appInfo) {
            ResourceName name = new ResourceName(packageName, resid);
            CharSequence text = getCachedString(name);
            if (text != null) {
                return text;
            }
            if (appInfo == null) {
                try {
                    appInfo = getApplicationInfo(packageName, 0);
                } catch (NameNotFoundException e) {
                    return null;
                }
            }
            try {
                Resources r = getResourcesForApplication(appInfo);
                text = r.getText(resid);
                putCachedString(name, text);
                return text;
            } catch (NameNotFoundException e) {
                Log.w("PackageManager", "Failure retrieving resources for"
                        + appInfo.packageName);
            } catch (RuntimeException e) {
                // If an exception was thrown, fall through to return
                // default icon.
                Log.w("PackageManager", "Failure retrieving text 0x"
                        + Integer.toHexString(resid) + " in package "
                        + packageName, e);
            }
            return null;
        }

        @Override
        public XmlResourceParser getXml(String packageName, int resid,
                ApplicationInfo appInfo) {
            if (appInfo == null) {
                try {
                    appInfo = getApplicationInfo(packageName, 0);
                } catch (NameNotFoundException e) {
                    return null;
                }
            }
            try {
                Resources r = getResourcesForApplication(appInfo);
                return r.getXml(resid);
            } catch (RuntimeException e) {
                // If an exception was thrown, fall through to return
                // default icon.
                Log.w("PackageManager", "Failure retrieving xml 0x"
                        + Integer.toHexString(resid) + " in package "
                        + packageName, e);
            } catch (NameNotFoundException e) {
                Log.w("PackageManager", "Failure retrieving resources for"
                        + appInfo.packageName);
            }
            return null;
        }

        @Override
        public CharSequence getApplicationLabel(ApplicationInfo info) {
            return info.loadLabel(this);
        }

        @Override
        public void installPackage(Uri packageURI, IPackageInstallObserver observer, int flags,
                String installerPackageName) {
            try {
                mPM.installPackage(packageURI, observer, flags, installerPackageName);
            } catch (RemoteException e) {
                // Should never happen!
            }
        }

        @Override
        public void movePackage(String packageName, IPackageMoveObserver observer, int flags) {
            try {
                mPM.movePackage(packageName, observer, flags);
            } catch (RemoteException e) {
                // Should never happen!
            }
        }

        @Override
        public String getInstallerPackageName(String packageName) {
            try {
                return mPM.getInstallerPackageName(packageName);
            } catch (RemoteException e) {
                // Should never happen!
            }
            return null;
        }

        @Override
        public void deletePackage(String packageName, IPackageDeleteObserver observer, int flags) {
            try {
                mPM.deletePackage(packageName, observer, flags);
            } catch (RemoteException e) {
                // Should never happen!
            }
        }
        @Override
        public void clearApplicationUserData(String packageName,
                IPackageDataObserver observer) {
            try {
                mPM.clearApplicationUserData(packageName, observer);
            } catch (RemoteException e) {
                // Should never happen!
            }
        }
        @Override
        public void deleteApplicationCacheFiles(String packageName,
                IPackageDataObserver observer) {
            try {
                mPM.deleteApplicationCacheFiles(packageName, observer);
            } catch (RemoteException e) {
                // Should never happen!
            }
        }
        @Override
        public void freeStorageAndNotify(long idealStorageSize, IPackageDataObserver observer) {
            try {
                mPM.freeStorageAndNotify(idealStorageSize, observer);
            } catch (RemoteException e) {
                // Should never happen!
            }
        }

        @Override
        public void freeStorage(long freeStorageSize, IntentSender pi) {
            try {
                mPM.freeStorage(freeStorageSize, pi);
            } catch (RemoteException e) {
                // Should never happen!
            }
        }

        @Override
        public void getPackageSizeInfo(String packageName,
                IPackageStatsObserver observer) {
            try {
                mPM.getPackageSizeInfo(packageName, observer);
            } catch (RemoteException e) {
                // Should never happen!
            }
        }
        @Override
        public void addPackageToPreferred(String packageName) {
            try {
                mPM.addPackageToPreferred(packageName);
            } catch (RemoteException e) {
                // Should never happen!
            }
        }

        @Override
        public void removePackageFromPreferred(String packageName) {
            try {
                mPM.removePackageFromPreferred(packageName);
            } catch (RemoteException e) {
                // Should never happen!
            }
        }

        @Override
        public List<PackageInfo> getPreferredPackages(int flags) {
            try {
                return mPM.getPreferredPackages(flags);
            } catch (RemoteException e) {
                // Should never happen!
            }
            return new ArrayList<PackageInfo>();
        }

        @Override
        public void addPreferredActivity(IntentFilter filter,
                int match, ComponentName[] set, ComponentName activity) {
            try {
                mPM.addPreferredActivity(filter, match, set, activity);
            } catch (RemoteException e) {
                // Should never happen!
            }
        }

        @Override
        public void replacePreferredActivity(IntentFilter filter,
                int match, ComponentName[] set, ComponentName activity) {
            try {
                mPM.replacePreferredActivity(filter, match, set, activity);
            } catch (RemoteException e) {
                // Should never happen!
            }
        }

        @Override
        public void clearPackagePreferredActivities(String packageName) {
            try {
                mPM.clearPackagePreferredActivities(packageName);
            } catch (RemoteException e) {
                // Should never happen!
            }
        }

        @Override
        public int getPreferredActivities(List<IntentFilter> outFilters,
                List<ComponentName> outActivities, String packageName) {
            try {
                return mPM.getPreferredActivities(outFilters, outActivities, packageName);
            } catch (RemoteException e) {
                // Should never happen!
            }
            return 0;
        }

        @Override
        public void setComponentEnabledSetting(ComponentName componentName,
                int newState, int flags) {
            try {
                mPM.setComponentEnabledSetting(componentName, newState, flags);
            } catch (RemoteException e) {
                // Should never happen!
            }
        }

        @Override
        public int getComponentEnabledSetting(ComponentName componentName) {
            try {
                return mPM.getComponentEnabledSetting(componentName);
            } catch (RemoteException e) {
                // Should never happen!
            }
            return PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
        }

        @Override
        public void setApplicationEnabledSetting(String packageName,
                int newState, int flags) {
            try {
                mPM.setApplicationEnabledSetting(packageName, newState, flags);
            } catch (RemoteException e) {
                // Should never happen!
            }
        }

        @Override
        public int getApplicationEnabledSetting(String packageName) {
            try {
                return mPM.getApplicationEnabledSetting(packageName);
            } catch (RemoteException e) {
                // Should never happen!
            }
            return PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
        }

        @Override
        public void setPackageObbPath(String packageName, String path) {
            try {
                mPM.setPackageObbPath(packageName, path);
            } catch (RemoteException e) {
                // Should never happen!
            }
        }

        private final ContextImpl mContext;
        private final IPackageManager mPM;

        private static final Object sSync = new Object();
        private static HashMap<ResourceName, WeakReference<Drawable> > sIconCache
                = new HashMap<ResourceName, WeakReference<Drawable> >();
        private static HashMap<ResourceName, WeakReference<CharSequence> > sStringCache
                = new HashMap<ResourceName, WeakReference<CharSequence> >();
    }

    // ----------------------------------------------------------------------
    // ----------------------------------------------------------------------
    // ----------------------------------------------------------------------

    private static final class SharedPreferencesImpl implements SharedPreferences {

        private final File mFile;
        private final File mBackupFile;
        private final int mMode;
        private Map mMap;
        private final FileStatus mFileStatus = new FileStatus();
        private long mTimestamp;

        private static final Object mContent = new Object();
        private WeakHashMap<OnSharedPreferenceChangeListener, Object> mListeners;

        SharedPreferencesImpl(
            File file, int mode, Map initialContents) {
            mFile = file;
            mBackupFile = makeBackupFile(file);
            mMode = mode;
            mMap = initialContents != null ? initialContents : new HashMap();
            if (FileUtils.getFileStatus(file.getPath(), mFileStatus)) {
                mTimestamp = mFileStatus.mtime;
            }
            mListeners = new WeakHashMap<OnSharedPreferenceChangeListener, Object>();
        }

        public boolean hasFileChanged() {
            synchronized (this) {
                if (!FileUtils.getFileStatus(mFile.getPath(), mFileStatus)) {
                    return true;
                }
                return mTimestamp != mFileStatus.mtime;
            }
        }

        public void replace(Map newContents) {
            if (newContents != null) {
                synchronized (this) {
                    mMap = newContents;
                }
            }
        }

        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
            synchronized(this) {
                mListeners.put(listener, mContent);
            }
        }

        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
            synchronized(this) {
                mListeners.remove(listener);
            }
        }

        public Map<String, ?> getAll() {
            synchronized(this) {
                //noinspection unchecked
                return new HashMap(mMap);
            }
        }

        public String getString(String key, String defValue) {
            synchronized (this) {
                String v = (String)mMap.get(key);
                return v != null ? v : defValue;
            }
        }

        public int getInt(String key, int defValue) {
            synchronized (this) {
                Integer v = (Integer)mMap.get(key);
                return v != null ? v : defValue;
            }
        }
        public long getLong(String key, long defValue) {
            synchronized (this) {
                Long v = (Long) mMap.get(key);
                return v != null ? v : defValue;
            }
        }
        public float getFloat(String key, float defValue) {
            synchronized (this) {
                Float v = (Float)mMap.get(key);
                return v != null ? v : defValue;
            }
        }
        public boolean getBoolean(String key, boolean defValue) {
            synchronized (this) {
                Boolean v = (Boolean)mMap.get(key);
                return v != null ? v : defValue;
            }
        }

        public boolean contains(String key) {
            synchronized (this) {
                return mMap.containsKey(key);
            }
        }

        public final class EditorImpl implements Editor {
            private final Map<String, Object> mModified = Maps.newHashMap();
            private boolean mClear = false;

            public Editor putString(String key, String value) {
                synchronized (this) {
                    mModified.put(key, value);
                    return this;
                }
            }
            public Editor putInt(String key, int value) {
                synchronized (this) {
                    mModified.put(key, value);
                    return this;
                }
            }
            public Editor putLong(String key, long value) {
                synchronized (this) {
                    mModified.put(key, value);
                    return this;
                }
            }
            public Editor putFloat(String key, float value) {
                synchronized (this) {
                    mModified.put(key, value);
                    return this;
                }
            }
            public Editor putBoolean(String key, boolean value) {
                synchronized (this) {
                    mModified.put(key, value);
                    return this;
                }
            }

            public Editor remove(String key) {
                synchronized (this) {
                    mModified.put(key, this);
                    return this;
                }
            }

            public Editor clear() {
                synchronized (this) {
                    mClear = true;
                    return this;
                }
            }

            public boolean commit() {
                boolean returnValue;

                boolean hasListeners;
                List<String> keysModified = null;
                Set<OnSharedPreferenceChangeListener> listeners = null;

                synchronized (SharedPreferencesImpl.this) {
                    hasListeners = mListeners.size() > 0;
                    if (hasListeners) {
                        keysModified = new ArrayList<String>();
                        listeners =
                                new HashSet<OnSharedPreferenceChangeListener>(mListeners.keySet());
                    }

                    synchronized (this) {
                        if (mClear) {
                            mMap.clear();
                            mClear = false;
                        }

                        for (Entry<String, Object> e : mModified.entrySet()) {
                            String k = e.getKey();
                            Object v = e.getValue();
                            if (v == this) {
                                mMap.remove(k);
                            } else {
                                mMap.put(k, v);
                            }

                            if (hasListeners) {
                                keysModified.add(k);
                            }
                        }

                        mModified.clear();
                    }

                    returnValue = writeFileLocked();
                }

                if (hasListeners) {
                    for (int i = keysModified.size() - 1; i >= 0; i--) {
                        final String key = keysModified.get(i);
                        for (OnSharedPreferenceChangeListener listener : listeners) {
                            if (listener != null) {
                                listener.onSharedPreferenceChanged(SharedPreferencesImpl.this, key);
                            }
                        }
                    }
                }

                return returnValue;
            }
        }

        public Editor edit() {
            return new EditorImpl();
        }

        private FileOutputStream createFileOutputStream(File file) {
            FileOutputStream str = null;
            try {
                str = new FileOutputStream(file);
            } catch (FileNotFoundException e) {
                File parent = file.getParentFile();
                if (!parent.mkdir()) {
                    Log.e(TAG, "Couldn't create directory for SharedPreferences file " + file);
                    return null;
                }
                FileUtils.setPermissions(
                    parent.getPath(),
                    FileUtils.S_IRWXU|FileUtils.S_IRWXG|FileUtils.S_IXOTH,
                    -1, -1);
                try {
                    str = new FileOutputStream(file);
                } catch (FileNotFoundException e2) {
                    Log.e(TAG, "Couldn't create SharedPreferences file " + file, e2);
                }
            }
            return str;
        }

        private boolean writeFileLocked() {
            // Rename the current file so it may be used as a backup during the next read
            if (mFile.exists()) {
                if (!mBackupFile.exists()) {
                    if (!mFile.renameTo(mBackupFile)) {
                        Log.e(TAG, "Couldn't rename file " + mFile
                                + " to backup file " + mBackupFile);
                        return false;
                    }
                } else {
                    mFile.delete();
                }
            }

            // Attempt to write the file, delete the backup and return true as atomically as
            // possible.  If any exception occurs, delete the new file; next time we will restore
            // from the backup.
            try {
                FileOutputStream str = createFileOutputStream(mFile);
                if (str == null) {
                    return false;
                }
                XmlUtils.writeMapXml(mMap, str);
                str.close();
                setFilePermissionsFromMode(mFile.getPath(), mMode, 0);
                if (FileUtils.getFileStatus(mFile.getPath(), mFileStatus)) {
                    mTimestamp = mFileStatus.mtime;
                }

                // Writing was successful, delete the backup file if there is one.
                mBackupFile.delete();
                return true;
            } catch (XmlPullParserException e) {
                Log.w(TAG, "writeFileLocked: Got exception:", e);
            } catch (IOException e) {
                Log.w(TAG, "writeFileLocked: Got exception:", e);
            }
            // Clean up an unsuccessfully written file
            if (mFile.exists()) {
                if (!mFile.delete()) {
                    Log.e(TAG, "Couldn't clean up partially-written file " + mFile);
                }
            }
            return false;
        }
    }
}
