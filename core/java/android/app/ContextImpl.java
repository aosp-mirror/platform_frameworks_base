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
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.IPackageDataObserver;
import android.content.pm.IPackageDeleteObserver;
import android.content.pm.IPackageInstallObserver;
import android.content.pm.IPackageMoveObserver;
import android.content.pm.IPackageManager;
import android.content.pm.IPackageStatsObserver;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDatabase.CursorFactory;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.hardware.SensorManager;
import android.location.CountryDetector;
import android.location.ICountryDetector;
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
import android.os.Build;
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
import android.content.ClipboardManager;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;

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

    private static final Object sSync = new Object();
    private static AlarmManager sAlarmManager;
    private static PowerManager sPowerManager;
    private static ConnectivityManager sConnectivityManager;
    private static ThrottleManager sThrottleManager;
    private static WifiManager sWifiManager;
    private static LocationManager sLocationManager;
    private static CountryDetector sCountryDetector;
    private static final HashMap<String, SharedPreferencesImpl> sSharedPrefs =
            new HashMap<String, SharedPreferencesImpl>();

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
    private DownloadManager mDownloadManager = null;

    private final Object mSync = new Object();

    private File mDatabasesDir;
    private File mPreferencesDir;
    private File mFilesDir;
    private File mCacheDir;
    private File mExternalFilesDir;
    private File mExternalCacheDir;

    private static final String[] EMPTY_FILE_LIST = {};

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
                final Context outerContext = getOuterContext();
                mThemeResource = (outerContext.getApplicationInfo().targetSdkVersion
                        >= Build.VERSION_CODES.HONEYCOMB)
                                ? com.android.internal.R.style.Theme_Holo
                                : com.android.internal.R.style.Theme;
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

    static File makeBackupFile(File prefsFile) {
        return new File(prefsFile.getPath() + ".bak");
    }

    public File getSharedPrefsFile(String name) {
        return makeFilename(getPreferencesDir(), name + ".xml");
    }

    @Override
    public SharedPreferences getSharedPreferences(String name, int mode) {
        SharedPreferencesImpl sp;
        File prefsFile;
        boolean needInitialLoad = false;
        synchronized (sSharedPrefs) {
            sp = sSharedPrefs.get(name);
            if (sp != null && !sp.hasFileChangedUnexpectedly()) {
                return sp;
            }
            prefsFile = getSharedPrefsFile(name);
            if (sp == null) {
                sp = new SharedPreferencesImpl(prefsFile, mode, null);
                sSharedPrefs.put(name, sp);
                needInitialLoad = true;
            }
        }

        synchronized (sp) {
            if (needInitialLoad && sp.isLoaded()) {
                // lost the race to load; another thread handled it
                return sp;
            }
            File backup = makeBackupFile(prefsFile);
            if (backup.exists()) {
                prefsFile.delete();
                backup.renameTo(prefsFile);
            }

            // Debugging
            if (prefsFile.exists() && !prefsFile.canRead()) {
                Log.w(TAG, "Attempt to read preferences file " + prefsFile + " without permission");
            }

            Map map = null;
            if (prefsFile.exists() && prefsFile.canRead()) {
                try {
                    FileInputStream str = new FileInputStream(prefsFile);
                    map = XmlUtils.readMapXml(str);
                    str.close();
                } catch (XmlPullParserException e) {
                    Log.w(TAG, "getSharedPreferences", e);
                } catch (FileNotFoundException e) {
                    Log.w(TAG, "getSharedPreferences", e);
                } catch (IOException e) {
                    Log.w(TAG, "getSharedPreferences", e);
                }
            }
            sp.replace(map);
        }
        return sp;
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
    public SQLiteDatabase openOrCreateDatabase(String name, int mode, CursorFactory factory,
            DatabaseErrorHandler errorHandler) {
        File f = validateFilePath(name, true);
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(f.getPath(), factory, errorHandler);
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
            getOuterContext(), mMainThread.getApplicationThread(), null,
            (Activity)null, intent, -1);
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
        } else if (COUNTRY_DETECTOR.equals(name)) {
            return getCountryDetector();
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
        } else if (DOWNLOAD_SERVICE.equals(name)) {
            return getDownloadManager();
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
                final Context outerContext = getOuterContext();
                mNotificationManager = new NotificationManager(
                        new ContextThemeWrapper(outerContext,
                                outerContext.getApplicationInfo().targetSdkVersion >=
                                    Build.VERSION_CODES.HONEYCOMB
                                ? com.android.internal.R.style.Theme_Holo_Dialog
                                : com.android.internal.R.style.Theme_Dialog),
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

    private CountryDetector getCountryDetector() {
        synchronized (sSync) {
            if (sCountryDetector == null) {
                IBinder b = ServiceManager.getService(COUNTRY_DETECTOR);
                ICountryDetector service = ICountryDetector.Stub.asInterface(b);
                sCountryDetector = new CountryDetector(service);
            }
        }
        return sCountryDetector;
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
        if (service == null) {
            // Don't return a DropBoxManager that will NPE upon use.
            // This also avoids caching a broken DropBoxManager in
            // getDropBoxManager during early boot, before the
            // DROPBOX_SERVICE is registered.
            return null;
        }
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

    private DownloadManager getDownloadManager() {
        synchronized (mSync) {
            if (mDownloadManager == null) {
                mDownloadManager = new DownloadManager(getContentResolver(), getPackageName());
            }
        }
        return mDownloadManager;
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
        mOuterContext = this;
    }

    /**
     * Create a new ApplicationContext from an existing one.  The new one
     * works and operates the same as the one it is copying.
     *
     * @param context Existing application context.
     */
    public ContextImpl(ContextImpl context) {
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

    static void setFilePermissionsFromMode(String name, int mode,
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
        public ApplicationContentResolver(Context context, ActivityThread mainThread) {
            super(context);
            mMainThread = mainThread;
        }

        @Override
        protected IContentProvider acquireProvider(Context context, String name) {
            return mMainThread.acquireProvider(context, name);
        }

        @Override
        protected IContentProvider acquireExistingProvider(Context context, String name) {
            return mMainThread.acquireExistingProvider(context, name);
        }

        @Override
        public boolean releaseProvider(IContentProvider provider) {
            return mMainThread.releaseProvider(provider);
        }

        private final ActivityThread mMainThread;
    }
}
