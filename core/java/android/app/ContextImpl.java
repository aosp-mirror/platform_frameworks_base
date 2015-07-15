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

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.IContentProvider;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.ReceiverCallNotAllowedException;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.AssetManager;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDatabase.CursorFactory;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.storage.IMountService;
import android.util.AndroidRuntimeException;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Slog;
import android.view.Display;
import android.view.DisplayAdjustments;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

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
        if (receiver == null) {
            // Allow retrieving current sticky broadcast; this is safe since we
            // aren't actually registering a receiver.
            return super.registerReceiver(null, filter, broadcastPermission, scheduler);
        } else {
            throw new ReceiverCallNotAllowedException(
                    "BroadcastReceiver components are not allowed to register to receive intents");
        }
    }

    @Override
    public Intent registerReceiverAsUser(BroadcastReceiver receiver, UserHandle user,
            IntentFilter filter, String broadcastPermission, Handler scheduler) {
        if (receiver == null) {
            // Allow retrieving current sticky broadcast; this is safe since we
            // aren't actually registering a receiver.
            return super.registerReceiverAsUser(null, user, filter, broadcastPermission, scheduler);
        } else {
            throw new ReceiverCallNotAllowedException(
                    "BroadcastReceiver components are not allowed to register to receive intents");
        }
    }

    @Override
    public boolean bindService(Intent service, ServiceConnection conn, int flags) {
        throw new ReceiverCallNotAllowedException(
                "BroadcastReceiver components are not allowed to bind to services");
    }
}

/**
 * Common implementation of Context API, which provides the base
 * context object for Activity and other application components.
 */
class ContextImpl extends Context {
    private final static String TAG = "ContextImpl";
    private final static boolean DEBUG = false;

    /**
     * Map from package name, to preference name, to cached preferences.
     */
    private static ArrayMap<String, ArrayMap<String, SharedPreferencesImpl>> sSharedPrefs;

    final ActivityThread mMainThread;
    final LoadedApk mPackageInfo;

    private final IBinder mActivityToken;

    private final UserHandle mUser;

    private final ApplicationContentResolver mContentResolver;

    private final String mBasePackageName;
    private final String mOpPackageName;

    private final ResourcesManager mResourcesManager;
    private final Resources mResources;
    private final Display mDisplay; // may be null if default display
    private final DisplayAdjustments mDisplayAdjustments = new DisplayAdjustments();

    private final boolean mRestricted;

    private Context mOuterContext;
    private int mThemeResource = 0;
    private Resources.Theme mTheme = null;
    private PackageManager mPackageManager;
    private Context mReceiverRestrictedContext = null;

    private final Object mSync = new Object();

    @GuardedBy("mSync")
    private File mDatabasesDir;
    @GuardedBy("mSync")
    private File mPreferencesDir;
    @GuardedBy("mSync")
    private File mFilesDir;
    @GuardedBy("mSync")
    private File mNoBackupFilesDir;
    @GuardedBy("mSync")
    private File mCacheDir;
    @GuardedBy("mSync")
    private File mCodeCacheDir;

    @GuardedBy("mSync")
    private File[] mExternalObbDirs;
    @GuardedBy("mSync")
    private File[] mExternalFilesDirs;
    @GuardedBy("mSync")
    private File[] mExternalCacheDirs;
    @GuardedBy("mSync")
    private File[] mExternalMediaDirs;

    private static final String[] EMPTY_STRING_ARRAY = {};

    // The system service cache for the system services that are cached per-ContextImpl.
    final Object[] mServiceCache = SystemServiceRegistry.createServiceCache();

    static ContextImpl getImpl(Context context) {
        Context nextContext;
        while ((context instanceof ContextWrapper) &&
                (nextContext=((ContextWrapper)context).getBaseContext()) != null) {
            context = nextContext;
        }
        return (ContextImpl)context;
    }

    @Override
    public AssetManager getAssets() {
        return getResources().getAssets();
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
    public void setTheme(int resId) {
        if (mThemeResource != resId) {
            mThemeResource = resId;
            initializeTheme();
        }
    }

    @Override
    public int getThemeResId() {
        return mThemeResource;
    }

    @Override
    public Resources.Theme getTheme() {
        if (mTheme != null) {
            return mTheme;
        }

        mThemeResource = Resources.selectDefaultTheme(mThemeResource,
                getOuterContext().getApplicationInfo().targetSdkVersion);
        initializeTheme();

        return mTheme;
    }

    private void initializeTheme() {
        if (mTheme == null) {
            mTheme = mResources.newTheme();
        }
        mTheme.applyStyle(mThemeResource, true);
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
        // No mPackageInfo means this is a Context for the system itself,
        // and this here is its name.
        return "android";
    }

    /** @hide */
    @Override
    public String getBasePackageName() {
        return mBasePackageName != null ? mBasePackageName : getPackageName();
    }

    /** @hide */
    @Override
    public String getOpPackageName() {
        return mOpPackageName != null ? mOpPackageName : getBasePackageName();
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

    @Override
    public File getSharedPrefsFile(String name) {
        return makeFilename(getPreferencesDir(), name + ".xml");
    }

    @Override
    public SharedPreferences getSharedPreferences(String name, int mode) {
        SharedPreferencesImpl sp;
        synchronized (ContextImpl.class) {
            if (sSharedPrefs == null) {
                sSharedPrefs = new ArrayMap<String, ArrayMap<String, SharedPreferencesImpl>>();
            }

            final String packageName = getPackageName();
            ArrayMap<String, SharedPreferencesImpl> packagePrefs = sSharedPrefs.get(packageName);
            if (packagePrefs == null) {
                packagePrefs = new ArrayMap<String, SharedPreferencesImpl>();
                sSharedPrefs.put(packageName, packagePrefs);
            }

            // At least one application in the world actually passes in a null
            // name.  This happened to work because when we generated the file name
            // we would stringify it to "null.xml".  Nice.
            if (mPackageInfo.getApplicationInfo().targetSdkVersion <
                    Build.VERSION_CODES.KITKAT) {
                if (name == null) {
                    name = "null";
                }
            }

            sp = packagePrefs.get(name);
            if (sp == null) {
                File prefsFile = getSharedPrefsFile(name);
                sp = new SharedPreferencesImpl(prefsFile, mode);
                packagePrefs.put(name, sp);
                return sp;
            }
        }
        if ((mode & Context.MODE_MULTI_PROCESS) != 0 ||
            getApplicationInfo().targetSdkVersion < android.os.Build.VERSION_CODES.HONEYCOMB) {
            // If somebody else (some other process) changed the prefs
            // file behind our back, we reload it.  This has been the
            // historical (if undocumented) behavior.
            sp.startReloadIfChangedUnexpectedly();
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

    // Common-path handling of app data dir creation
    private static File createFilesDirLocked(File file) {
        if (!file.exists()) {
            if (!file.mkdirs()) {
                if (file.exists()) {
                    // spurious failure; probably racing with another process for this app
                    return file;
                }
                Log.w(TAG, "Unable to create files subdir " + file.getPath());
                return null;
            }
            FileUtils.setPermissions(
                    file.getPath(),
                    FileUtils.S_IRWXU|FileUtils.S_IRWXG|FileUtils.S_IXOTH,
                    -1, -1);
        }
        return file;
    }

    @Override
    public File getFilesDir() {
        synchronized (mSync) {
            if (mFilesDir == null) {
                mFilesDir = new File(getDataDirFile(), "files");
            }
            return createFilesDirLocked(mFilesDir);
        }
    }

    @Override
    public File getNoBackupFilesDir() {
        synchronized (mSync) {
            if (mNoBackupFilesDir == null) {
                mNoBackupFilesDir = new File(getDataDirFile(), "no_backup");
            }
            return createFilesDirLocked(mNoBackupFilesDir);
        }
    }

    @Override
    public File getExternalFilesDir(String type) {
        // Operates on primary external storage
        return getExternalFilesDirs(type)[0];
    }

    @Override
    public File[] getExternalFilesDirs(String type) {
        synchronized (mSync) {
            if (mExternalFilesDirs == null) {
                mExternalFilesDirs = Environment.buildExternalStorageAppFilesDirs(getPackageName());
            }

            // Splice in requested type, if any
            File[] dirs = mExternalFilesDirs;
            if (type != null) {
                dirs = Environment.buildPaths(dirs, type);
            }

            // Create dirs if needed
            return ensureDirsExistOrFilter(dirs);
        }
    }

    @Override
    public File getObbDir() {
        // Operates on primary external storage
        return getObbDirs()[0];
    }

    @Override
    public File[] getObbDirs() {
        synchronized (mSync) {
            if (mExternalObbDirs == null) {
                mExternalObbDirs = Environment.buildExternalStorageAppObbDirs(getPackageName());
            }

            // Create dirs if needed
            return ensureDirsExistOrFilter(mExternalObbDirs);
        }
    }

    @Override
    public File getCacheDir() {
        synchronized (mSync) {
            if (mCacheDir == null) {
                mCacheDir = new File(getDataDirFile(), "cache");
            }
            return createFilesDirLocked(mCacheDir);
        }
    }

    @Override
    public File getCodeCacheDir() {
        synchronized (mSync) {
            if (mCodeCacheDir == null) {
                mCodeCacheDir = new File(getDataDirFile(), "code_cache");
            }
            return createFilesDirLocked(mCodeCacheDir);
        }
    }

    @Override
    public File getExternalCacheDir() {
        // Operates on primary external storage
        return getExternalCacheDirs()[0];
    }

    @Override
    public File[] getExternalCacheDirs() {
        synchronized (mSync) {
            if (mExternalCacheDirs == null) {
                mExternalCacheDirs = Environment.buildExternalStorageAppCacheDirs(getPackageName());
            }

            // Create dirs if needed
            return ensureDirsExistOrFilter(mExternalCacheDirs);
        }
    }

    @Override
    public File[] getExternalMediaDirs() {
        synchronized (mSync) {
            if (mExternalMediaDirs == null) {
                mExternalMediaDirs = Environment.buildExternalStorageAppMediaDirs(getPackageName());
            }

            // Create dirs if needed
            return ensureDirsExistOrFilter(mExternalMediaDirs);
        }
    }

    @Override
    public File getFileStreamPath(String name) {
        return makeFilename(getFilesDir(), name);
    }

    @Override
    public String[] fileList() {
        final String[] list = getFilesDir().list();
        return (list != null) ? list : EMPTY_STRING_ARRAY;
    }

    @Override
    public SQLiteDatabase openOrCreateDatabase(String name, int mode, CursorFactory factory) {
        return openOrCreateDatabase(name, mode, factory, null);
    }

    @Override
    public SQLiteDatabase openOrCreateDatabase(String name, int mode, CursorFactory factory,
            DatabaseErrorHandler errorHandler) {
        File f = validateFilePath(name, true);
        int flags = SQLiteDatabase.CREATE_IF_NECESSARY;
        if ((mode & MODE_ENABLE_WRITE_AHEAD_LOGGING) != 0) {
            flags |= SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING;
        }
        SQLiteDatabase db = SQLiteDatabase.openDatabase(f.getPath(), factory, flags, errorHandler);
        setFilePermissionsFromMode(f.getPath(), mode, 0);
        return db;
    }

    @Override
    public boolean deleteDatabase(String name) {
        try {
            File f = validateFilePath(name, false);
            return SQLiteDatabase.deleteDatabase(f);
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
        return (list != null) ? list : EMPTY_STRING_ARRAY;
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
    @Deprecated
    public Drawable getWallpaper() {
        return getWallpaperManager().getDrawable();
    }

    @Override
    @Deprecated
    public Drawable peekWallpaper() {
        return getWallpaperManager().peekDrawable();
    }

    @Override
    @Deprecated
    public int getWallpaperDesiredMinimumWidth() {
        return getWallpaperManager().getDesiredMinimumWidth();
    }

    @Override
    @Deprecated
    public int getWallpaperDesiredMinimumHeight() {
        return getWallpaperManager().getDesiredMinimumHeight();
    }

    @Override
    @Deprecated
    public void setWallpaper(Bitmap bitmap) throws IOException {
        getWallpaperManager().setBitmap(bitmap);
    }

    @Override
    @Deprecated
    public void setWallpaper(InputStream data) throws IOException {
        getWallpaperManager().setStream(data);
    }

    @Override
    @Deprecated
    public void clearWallpaper() throws IOException {
        getWallpaperManager().clear();
    }

    private WallpaperManager getWallpaperManager() {
        return getSystemService(WallpaperManager.class);
    }

    @Override
    public void startActivity(Intent intent) {
        warnIfCallingFromSystemProcess();
        startActivity(intent, null);
    }

    /** @hide */
    @Override
    public void startActivityAsUser(Intent intent, UserHandle user) {
        startActivityAsUser(intent, null, user);
    }

    @Override
    public void startActivity(Intent intent, Bundle options) {
        warnIfCallingFromSystemProcess();
        if ((intent.getFlags()&Intent.FLAG_ACTIVITY_NEW_TASK) == 0) {
            throw new AndroidRuntimeException(
                    "Calling startActivity() from outside of an Activity "
                    + " context requires the FLAG_ACTIVITY_NEW_TASK flag."
                    + " Is this really what you want?");
        }
        mMainThread.getInstrumentation().execStartActivity(
                getOuterContext(), mMainThread.getApplicationThread(), null,
                (Activity) null, intent, -1, options);
    }

    /** @hide */
    @Override
    public void startActivityAsUser(Intent intent, Bundle options, UserHandle user) {
        try {
            ActivityManagerNative.getDefault().startActivityAsUser(
                mMainThread.getApplicationThread(), getBasePackageName(), intent,
                intent.resolveTypeIfNeeded(getContentResolver()),
                null, null, 0, Intent.FLAG_ACTIVITY_NEW_TASK, null, options,
                user.getIdentifier());
        } catch (RemoteException e) {
            throw new RuntimeException("Failure from system", e);
        }
    }

    @Override
    public void startActivities(Intent[] intents) {
        warnIfCallingFromSystemProcess();
        startActivities(intents, null);
    }

    /** @hide */
    @Override
    public void startActivitiesAsUser(Intent[] intents, Bundle options, UserHandle userHandle) {
        if ((intents[0].getFlags()&Intent.FLAG_ACTIVITY_NEW_TASK) == 0) {
            throw new AndroidRuntimeException(
                    "Calling startActivities() from outside of an Activity "
                    + " context requires the FLAG_ACTIVITY_NEW_TASK flag on first Intent."
                    + " Is this really what you want?");
        }
        mMainThread.getInstrumentation().execStartActivitiesAsUser(
                getOuterContext(), mMainThread.getApplicationThread(), null,
                (Activity) null, intents, options, userHandle.getIdentifier());
    }

    @Override
    public void startActivities(Intent[] intents, Bundle options) {
        warnIfCallingFromSystemProcess();
        if ((intents[0].getFlags()&Intent.FLAG_ACTIVITY_NEW_TASK) == 0) {
            throw new AndroidRuntimeException(
                    "Calling startActivities() from outside of an Activity "
                    + " context requires the FLAG_ACTIVITY_NEW_TASK flag on first Intent."
                    + " Is this really what you want?");
        }
        mMainThread.getInstrumentation().execStartActivities(
                getOuterContext(), mMainThread.getApplicationThread(), null,
                (Activity) null, intents, options);
    }

    @Override
    public void startIntentSender(IntentSender intent,
            Intent fillInIntent, int flagsMask, int flagsValues, int extraFlags)
            throws IntentSender.SendIntentException {
        startIntentSender(intent, fillInIntent, flagsMask, flagsValues, extraFlags, null);
    }

    @Override
    public void startIntentSender(IntentSender intent, Intent fillInIntent,
            int flagsMask, int flagsValues, int extraFlags, Bundle options)
            throws IntentSender.SendIntentException {
        try {
            String resolvedType = null;
            if (fillInIntent != null) {
                fillInIntent.migrateExtraStreamToClipData();
                fillInIntent.prepareToLeaveProcess();
                resolvedType = fillInIntent.resolveTypeIfNeeded(getContentResolver());
            }
            int result = ActivityManagerNative.getDefault()
                .startActivityIntentSender(mMainThread.getApplicationThread(), intent,
                        fillInIntent, resolvedType, null, null,
                        0, flagsMask, flagsValues, options);
            if (result == ActivityManager.START_CANCELED) {
                throw new IntentSender.SendIntentException();
            }
            Instrumentation.checkStartActivityResult(result, null);
        } catch (RemoteException e) {
            throw new RuntimeException("Failure from system", e);
        }
    }

    @Override
    public void sendBroadcast(Intent intent) {
        warnIfCallingFromSystemProcess();
        String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
        try {
            intent.prepareToLeaveProcess();
            ActivityManagerNative.getDefault().broadcastIntent(
                    mMainThread.getApplicationThread(), intent, resolvedType, null,
                    Activity.RESULT_OK, null, null, null, AppOpsManager.OP_NONE, null, false, false,
                    getUserId());
        } catch (RemoteException e) {
            throw new RuntimeException("Failure from system", e);
        }
    }

    @Override
    public void sendBroadcast(Intent intent, String receiverPermission) {
        warnIfCallingFromSystemProcess();
        String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
        String[] receiverPermissions = receiverPermission == null ? null
                : new String[] {receiverPermission};
        try {
            intent.prepareToLeaveProcess();
            ActivityManagerNative.getDefault().broadcastIntent(
                    mMainThread.getApplicationThread(), intent, resolvedType, null,
                    Activity.RESULT_OK, null, null, receiverPermissions, AppOpsManager.OP_NONE,
                    null, false, false, getUserId());
        } catch (RemoteException e) {
            throw new RuntimeException("Failure from system", e);
        }
    }

    @Override
    public void sendBroadcastMultiplePermissions(Intent intent, String[] receiverPermissions) {
        warnIfCallingFromSystemProcess();
        String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
        try {
            intent.prepareToLeaveProcess();
            ActivityManagerNative.getDefault().broadcastIntent(
                    mMainThread.getApplicationThread(), intent, resolvedType, null,
                    Activity.RESULT_OK, null, null, receiverPermissions, AppOpsManager.OP_NONE,
                    null, false, false, getUserId());
        } catch (RemoteException e) {
            throw new RuntimeException("Failure from system", e);
        }
    }

    @Override
    public void sendBroadcast(Intent intent, String receiverPermission, Bundle options) {
        warnIfCallingFromSystemProcess();
        String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
        String[] receiverPermissions = receiverPermission == null ? null
                : new String[] {receiverPermission};
        try {
            intent.prepareToLeaveProcess();
            ActivityManagerNative.getDefault().broadcastIntent(
                    mMainThread.getApplicationThread(), intent, resolvedType, null,
                    Activity.RESULT_OK, null, null, receiverPermissions, AppOpsManager.OP_NONE,
                    options, false, false, getUserId());
        } catch (RemoteException e) {
            throw new RuntimeException("Failure from system", e);
        }
    }

    @Override
    public void sendBroadcast(Intent intent, String receiverPermission, int appOp) {
        warnIfCallingFromSystemProcess();
        String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
        String[] receiverPermissions = receiverPermission == null ? null
                : new String[] {receiverPermission};
        try {
            intent.prepareToLeaveProcess();
            ActivityManagerNative.getDefault().broadcastIntent(
                    mMainThread.getApplicationThread(), intent, resolvedType, null,
                    Activity.RESULT_OK, null, null, receiverPermissions, appOp, null, false, false,
                    getUserId());
        } catch (RemoteException e) {
            throw new RuntimeException("Failure from system", e);
        }
    }

    @Override
    public void sendOrderedBroadcast(Intent intent, String receiverPermission) {
        warnIfCallingFromSystemProcess();
        String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
        String[] receiverPermissions = receiverPermission == null ? null
                : new String[] {receiverPermission};
        try {
            intent.prepareToLeaveProcess();
            ActivityManagerNative.getDefault().broadcastIntent(
                    mMainThread.getApplicationThread(), intent, resolvedType, null,
                    Activity.RESULT_OK, null, null, receiverPermissions, AppOpsManager.OP_NONE,
                    null, true, false, getUserId());
        } catch (RemoteException e) {
            throw new RuntimeException("Failure from system", e);
        }
    }

    @Override
    public void sendOrderedBroadcast(Intent intent,
            String receiverPermission, BroadcastReceiver resultReceiver,
            Handler scheduler, int initialCode, String initialData,
            Bundle initialExtras) {
        sendOrderedBroadcast(intent, receiverPermission, AppOpsManager.OP_NONE,
                resultReceiver, scheduler, initialCode, initialData, initialExtras, null);
    }

    @Override
    public void sendOrderedBroadcast(Intent intent,
            String receiverPermission, Bundle options, BroadcastReceiver resultReceiver,
            Handler scheduler, int initialCode, String initialData,
            Bundle initialExtras) {
        sendOrderedBroadcast(intent, receiverPermission, AppOpsManager.OP_NONE,
                resultReceiver, scheduler, initialCode, initialData, initialExtras, options);
    }

    @Override
    public void sendOrderedBroadcast(Intent intent,
            String receiverPermission, int appOp, BroadcastReceiver resultReceiver,
            Handler scheduler, int initialCode, String initialData,
            Bundle initialExtras) {
        sendOrderedBroadcast(intent, receiverPermission, appOp,
                resultReceiver, scheduler, initialCode, initialData, initialExtras, null);
    }

    void sendOrderedBroadcast(Intent intent,
            String receiverPermission, int appOp, BroadcastReceiver resultReceiver,
            Handler scheduler, int initialCode, String initialData,
            Bundle initialExtras, Bundle options) {
        warnIfCallingFromSystemProcess();
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
        String[] receiverPermissions = receiverPermission == null ? null
                : new String[] {receiverPermission};
        try {
            intent.prepareToLeaveProcess();
            ActivityManagerNative.getDefault().broadcastIntent(
                mMainThread.getApplicationThread(), intent, resolvedType, rd,
                initialCode, initialData, initialExtras, receiverPermissions, appOp,
                    options, true, false, getUserId());
        } catch (RemoteException e) {
            throw new RuntimeException("Failure from system", e);
        }
    }

    @Override
    public void sendBroadcastAsUser(Intent intent, UserHandle user) {
        String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
        try {
            intent.prepareToLeaveProcess();
            ActivityManagerNative.getDefault().broadcastIntent(mMainThread.getApplicationThread(),
                    intent, resolvedType, null, Activity.RESULT_OK, null, null, null,
                    AppOpsManager.OP_NONE, null, false, false, user.getIdentifier());
        } catch (RemoteException e) {
            throw new RuntimeException("Failure from system", e);
        }
    }

    @Override
    public void sendBroadcastAsUser(Intent intent, UserHandle user,
            String receiverPermission) {
        sendBroadcastAsUser(intent, user, receiverPermission, AppOpsManager.OP_NONE);
    }

    @Override
    public void sendBroadcastAsUser(Intent intent, UserHandle user,
            String receiverPermission, int appOp) {
        String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
        String[] receiverPermissions = receiverPermission == null ? null
                : new String[] {receiverPermission};
        try {
            intent.prepareToLeaveProcess();
            ActivityManagerNative.getDefault().broadcastIntent(
                    mMainThread.getApplicationThread(), intent, resolvedType, null,
                    Activity.RESULT_OK, null, null, receiverPermissions, appOp, null, false, false,
                    user.getIdentifier());
        } catch (RemoteException e) {
            throw new RuntimeException("Failure from system", e);
        }
    }

    @Override
    public void sendOrderedBroadcastAsUser(Intent intent, UserHandle user,
            String receiverPermission, BroadcastReceiver resultReceiver, Handler scheduler,
            int initialCode, String initialData, Bundle initialExtras) {
        sendOrderedBroadcastAsUser(intent, user, receiverPermission, AppOpsManager.OP_NONE,
                null, resultReceiver, scheduler, initialCode, initialData, initialExtras);
    }

    @Override
    public void sendOrderedBroadcastAsUser(Intent intent, UserHandle user,
            String receiverPermission, int appOp, BroadcastReceiver resultReceiver,
            Handler scheduler, int initialCode, String initialData, Bundle initialExtras) {
        sendOrderedBroadcastAsUser(intent, user, receiverPermission, appOp,
                null, resultReceiver, scheduler, initialCode, initialData, initialExtras);
    }

    @Override
    public void sendOrderedBroadcastAsUser(Intent intent, UserHandle user,
            String receiverPermission, int appOp, Bundle options, BroadcastReceiver resultReceiver,
            Handler scheduler, int initialCode, String initialData, Bundle initialExtras) {
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
                rd = new LoadedApk.ReceiverDispatcher(resultReceiver, getOuterContext(),
                        scheduler, null, false).getIIntentReceiver();
            }
        }
        String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
        String[] receiverPermissions = receiverPermission == null ? null
                : new String[] {receiverPermission};
        try {
            intent.prepareToLeaveProcess();
            ActivityManagerNative.getDefault().broadcastIntent(
                mMainThread.getApplicationThread(), intent, resolvedType, rd,
                initialCode, initialData, initialExtras, receiverPermissions,
                    appOp, options, true, false, user.getIdentifier());
        } catch (RemoteException e) {
            throw new RuntimeException("Failure from system", e);
        }
    }

    @Override
    @Deprecated
    public void sendStickyBroadcast(Intent intent) {
        warnIfCallingFromSystemProcess();
        String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
        try {
            intent.prepareToLeaveProcess();
            ActivityManagerNative.getDefault().broadcastIntent(
                mMainThread.getApplicationThread(), intent, resolvedType, null,
                Activity.RESULT_OK, null, null, null, AppOpsManager.OP_NONE, null, false, true,
                getUserId());
        } catch (RemoteException e) {
            throw new RuntimeException("Failure from system", e);
        }
    }

    @Override
    @Deprecated
    public void sendStickyOrderedBroadcast(Intent intent,
            BroadcastReceiver resultReceiver,
            Handler scheduler, int initialCode, String initialData,
            Bundle initialExtras) {
        warnIfCallingFromSystemProcess();
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
            intent.prepareToLeaveProcess();
            ActivityManagerNative.getDefault().broadcastIntent(
                mMainThread.getApplicationThread(), intent, resolvedType, rd,
                initialCode, initialData, initialExtras, null,
                    AppOpsManager.OP_NONE, null, true, true, getUserId());
        } catch (RemoteException e) {
            throw new RuntimeException("Failure from system", e);
        }
    }

    @Override
    @Deprecated
    public void removeStickyBroadcast(Intent intent) {
        String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
        if (resolvedType != null) {
            intent = new Intent(intent);
            intent.setDataAndType(intent.getData(), resolvedType);
        }
        try {
            intent.prepareToLeaveProcess();
            ActivityManagerNative.getDefault().unbroadcastIntent(
                    mMainThread.getApplicationThread(), intent, getUserId());
        } catch (RemoteException e) {
            throw new RuntimeException("Failure from system", e);
        }
    }

    @Override
    @Deprecated
    public void sendStickyBroadcastAsUser(Intent intent, UserHandle user) {
        String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
        try {
            intent.prepareToLeaveProcess();
            ActivityManagerNative.getDefault().broadcastIntent(
                mMainThread.getApplicationThread(), intent, resolvedType, null,
                Activity.RESULT_OK, null, null, null, AppOpsManager.OP_NONE, null, false, true, user.getIdentifier());
        } catch (RemoteException e) {
            throw new RuntimeException("Failure from system", e);
        }
    }

    @Override
    @Deprecated
    public void sendStickyOrderedBroadcastAsUser(Intent intent,
            UserHandle user, BroadcastReceiver resultReceiver,
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
            intent.prepareToLeaveProcess();
            ActivityManagerNative.getDefault().broadcastIntent(
                mMainThread.getApplicationThread(), intent, resolvedType, rd,
                initialCode, initialData, initialExtras, null,
                    AppOpsManager.OP_NONE, null, true, true, user.getIdentifier());
        } catch (RemoteException e) {
            throw new RuntimeException("Failure from system", e);
        }
    }

    @Override
    @Deprecated
    public void removeStickyBroadcastAsUser(Intent intent, UserHandle user) {
        String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
        if (resolvedType != null) {
            intent = new Intent(intent);
            intent.setDataAndType(intent.getData(), resolvedType);
        }
        try {
            intent.prepareToLeaveProcess();
            ActivityManagerNative.getDefault().unbroadcastIntent(
                    mMainThread.getApplicationThread(), intent, user.getIdentifier());
        } catch (RemoteException e) {
            throw new RuntimeException("Failure from system", e);
        }
    }

    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        return registerReceiver(receiver, filter, null, null);
    }

    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
            String broadcastPermission, Handler scheduler) {
        return registerReceiverInternal(receiver, getUserId(),
                filter, broadcastPermission, scheduler, getOuterContext());
    }

    @Override
    public Intent registerReceiverAsUser(BroadcastReceiver receiver, UserHandle user,
            IntentFilter filter, String broadcastPermission, Handler scheduler) {
        return registerReceiverInternal(receiver, user.getIdentifier(),
                filter, broadcastPermission, scheduler, getOuterContext());
    }

    private Intent registerReceiverInternal(BroadcastReceiver receiver, int userId,
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
                    mMainThread.getApplicationThread(), mBasePackageName,
                    rd, filter, broadcastPermission, userId);
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

    private void validateServiceIntent(Intent service) {
        if (service.getComponent() == null && service.getPackage() == null) {
            if (getApplicationInfo().targetSdkVersion >= Build.VERSION_CODES.LOLLIPOP) {
                IllegalArgumentException ex = new IllegalArgumentException(
                        "Service Intent must be explicit: " + service);
                throw ex;
            } else {
                Log.w(TAG, "Implicit intents with startService are not safe: " + service
                        + " " + Debug.getCallers(2, 3));
            }
        }
    }

    @Override
    public ComponentName startService(Intent service) {
        warnIfCallingFromSystemProcess();
        return startServiceCommon(service, mUser);
    }

    @Override
    public boolean stopService(Intent service) {
        warnIfCallingFromSystemProcess();
        return stopServiceCommon(service, mUser);
    }

    @Override
    public ComponentName startServiceAsUser(Intent service, UserHandle user) {
        return startServiceCommon(service, user);
    }

    private ComponentName startServiceCommon(Intent service, UserHandle user) {
        try {
            validateServiceIntent(service);
            service.prepareToLeaveProcess();
            ComponentName cn = ActivityManagerNative.getDefault().startService(
                mMainThread.getApplicationThread(), service, service.resolveTypeIfNeeded(
                            getContentResolver()), getOpPackageName(), user.getIdentifier());
            if (cn != null) {
                if (cn.getPackageName().equals("!")) {
                    throw new SecurityException(
                            "Not allowed to start service " + service
                            + " without permission " + cn.getClassName());
                } else if (cn.getPackageName().equals("!!")) {
                    throw new SecurityException(
                            "Unable to start service " + service
                            + ": " + cn.getClassName());
                }
            }
            return cn;
        } catch (RemoteException e) {
            throw new RuntimeException("Failure from system", e);
        }
    }

    @Override
    public boolean stopServiceAsUser(Intent service, UserHandle user) {
        return stopServiceCommon(service, user);
    }

    private boolean stopServiceCommon(Intent service, UserHandle user) {
        try {
            validateServiceIntent(service);
            service.prepareToLeaveProcess();
            int res = ActivityManagerNative.getDefault().stopService(
                mMainThread.getApplicationThread(), service,
                service.resolveTypeIfNeeded(getContentResolver()), user.getIdentifier());
            if (res < 0) {
                throw new SecurityException(
                        "Not allowed to stop service " + service);
            }
            return res != 0;
        } catch (RemoteException e) {
            throw new RuntimeException("Failure from system", e);
        }
    }

    @Override
    public boolean bindService(Intent service, ServiceConnection conn,
            int flags) {
        warnIfCallingFromSystemProcess();
        return bindServiceCommon(service, conn, flags, Process.myUserHandle());
    }

    /** @hide */
    @Override
    public boolean bindServiceAsUser(Intent service, ServiceConnection conn, int flags,
            UserHandle user) {
        return bindServiceCommon(service, conn, flags, user);
    }

    private boolean bindServiceCommon(Intent service, ServiceConnection conn, int flags,
            UserHandle user) {
        IServiceConnection sd;
        if (conn == null) {
            throw new IllegalArgumentException("connection is null");
        }
        if (mPackageInfo != null) {
            sd = mPackageInfo.getServiceDispatcher(conn, getOuterContext(),
                    mMainThread.getHandler(), flags);
        } else {
            throw new RuntimeException("Not supported in system context");
        }
        validateServiceIntent(service);
        try {
            IBinder token = getActivityToken();
            if (token == null && (flags&BIND_AUTO_CREATE) == 0 && mPackageInfo != null
                    && mPackageInfo.getApplicationInfo().targetSdkVersion
                    < android.os.Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
                flags |= BIND_WAIVE_PRIORITY;
            }
            service.prepareToLeaveProcess();
            int res = ActivityManagerNative.getDefault().bindService(
                mMainThread.getApplicationThread(), getActivityToken(), service,
                service.resolveTypeIfNeeded(getContentResolver()),
                sd, flags, getOpPackageName(), user.getIdentifier());
            if (res < 0) {
                throw new SecurityException(
                        "Not allowed to bind to service " + service);
            }
            return res != 0;
        } catch (RemoteException e) {
            throw new RuntimeException("Failure from system", e);
        }
    }

    @Override
    public void unbindService(ServiceConnection conn) {
        if (conn == null) {
            throw new IllegalArgumentException("connection is null");
        }
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
            if (arguments != null) {
                arguments.setAllowFds(false);
            }
            return ActivityManagerNative.getDefault().startInstrumentation(
                    className, profileFile, 0, arguments, null, null, getUserId(),
                    null /* ABI override */);
        } catch (RemoteException e) {
            throw new RuntimeException("Failure from system", e);
        }
    }

    @Override
    public Object getSystemService(String name) {
        return SystemServiceRegistry.getSystemService(this, name);
    }

    @Override
    public String getSystemServiceName(Class<?> serviceClass) {
        return SystemServiceRegistry.getSystemServiceName(serviceClass);
    }

    @Override
    public int checkPermission(String permission, int pid, int uid) {
        if (permission == null) {
            throw new IllegalArgumentException("permission is null");
        }

        try {
            return ActivityManagerNative.getDefault().checkPermission(
                    permission, pid, uid);
        } catch (RemoteException e) {
            return PackageManager.PERMISSION_DENIED;
        }
    }

    /** @hide */
    @Override
    public int checkPermission(String permission, int pid, int uid, IBinder callerToken) {
        if (permission == null) {
            throw new IllegalArgumentException("permission is null");
        }

        try {
            return ActivityManagerNative.getDefault().checkPermissionWithToken(
                    permission, pid, uid, callerToken);
        } catch (RemoteException e) {
            return PackageManager.PERMISSION_DENIED;
        }
    }

    @Override
    public int checkCallingPermission(String permission) {
        if (permission == null) {
            throw new IllegalArgumentException("permission is null");
        }

        int pid = Binder.getCallingPid();
        if (pid != Process.myPid()) {
            return checkPermission(permission, pid, Binder.getCallingUid());
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

    @Override
    public int checkSelfPermission(String permission) {
        if (permission == null) {
            throw new IllegalArgumentException("permission is null");
        }

        return checkPermission(permission, Process.myPid(), Process.myUid());
    }

    private void enforce(
            String permission, int resultOfCheck,
            boolean selfToo, int uid, String message) {
        if (resultOfCheck != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(
                    (message != null ? (message + ": ") : "") +
                    (selfToo
                     ? "Neither user " + uid + " nor current process has "
                     : "uid " + uid + " does not have ") +
                    permission +
                    ".");
        }
    }

    @Override
    public void enforcePermission(
            String permission, int pid, int uid, String message) {
        enforce(permission,
                checkPermission(permission, pid, uid),
                false,
                uid,
                message);
    }

    @Override
    public void enforceCallingPermission(String permission, String message) {
        enforce(permission,
                checkCallingPermission(permission),
                false,
                Binder.getCallingUid(),
                message);
    }

    @Override
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
                    mMainThread.getApplicationThread(), toPackage,
                    ContentProvider.getUriWithoutUserId(uri), modeFlags, resolveUserId(uri));
        } catch (RemoteException e) {
        }
    }

    @Override
    public void revokeUriPermission(Uri uri, int modeFlags) {
         try {
            ActivityManagerNative.getDefault().revokeUriPermission(
                    mMainThread.getApplicationThread(),
                    ContentProvider.getUriWithoutUserId(uri), modeFlags, resolveUserId(uri));
        } catch (RemoteException e) {
        }
    }

    @Override
    public int checkUriPermission(Uri uri, int pid, int uid, int modeFlags) {
        try {
            return ActivityManagerNative.getDefault().checkUriPermission(
                    ContentProvider.getUriWithoutUserId(uri), pid, uid, modeFlags,
                    resolveUserId(uri), null);
        } catch (RemoteException e) {
            return PackageManager.PERMISSION_DENIED;
        }
    }

    /** @hide */
    @Override
    public int checkUriPermission(Uri uri, int pid, int uid, int modeFlags, IBinder callerToken) {
        try {
            return ActivityManagerNative.getDefault().checkUriPermission(
                    ContentProvider.getUriWithoutUserId(uri), pid, uid, modeFlags,
                    resolveUserId(uri), callerToken);
        } catch (RemoteException e) {
            return PackageManager.PERMISSION_DENIED;
        }
    }

    private int resolveUserId(Uri uri) {
        return ContentProvider.getUserIdFromUri(uri, getUserId());
    }

    @Override
    public int checkCallingUriPermission(Uri uri, int modeFlags) {
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
        StringBuilder builder = new StringBuilder();
        if ((uriModeFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
            builder.append("read and ");
        }
        if ((uriModeFlags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) {
            builder.append("write and ");
        }
        if ((uriModeFlags & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0) {
            builder.append("persistable and ");
        }
        if ((uriModeFlags & Intent.FLAG_GRANT_PREFIX_URI_PERMISSION) != 0) {
            builder.append("prefix and ");
        }

        if (builder.length() > 5) {
            builder.setLength(builder.length() - 5);
            return builder.toString();
        } else {
            throw new IllegalArgumentException("Unknown permission mode flags: " + uriModeFlags);
        }
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

    @Override
    public void enforceUriPermission(
            Uri uri, int pid, int uid, int modeFlags, String message) {
        enforceForUri(
                modeFlags, checkUriPermission(uri, pid, uid, modeFlags),
                false, uid, uri, message);
    }

    @Override
    public void enforceCallingUriPermission(
            Uri uri, int modeFlags, String message) {
        enforceForUri(
                modeFlags, checkCallingUriPermission(uri, modeFlags),
                false,
                Binder.getCallingUid(), uri, message);
    }

    @Override
    public void enforceCallingOrSelfUriPermission(
            Uri uri, int modeFlags, String message) {
        enforceForUri(
                modeFlags,
                checkCallingOrSelfUriPermission(uri, modeFlags), true,
                Binder.getCallingUid(), uri, message);
    }

    @Override
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

    /**
     * Logs a warning if the system process directly called a method such as
     * {@link #startService(Intent)} instead of {@link #startServiceAsUser(Intent, UserHandle)}.
     * The "AsUser" variants allow us to properly enforce the user's restrictions.
     */
    private void warnIfCallingFromSystemProcess() {
        if (Process.myUid() == Process.SYSTEM_UID) {
            Slog.w(TAG, "Calling a method in the system process without a qualified user: "
                    + Debug.getCallers(5));
        }
    }

    @Override
    public Context createApplicationContext(ApplicationInfo application, int flags)
            throws NameNotFoundException {
        LoadedApk pi = mMainThread.getPackageInfo(application, mResources.getCompatibilityInfo(),
                flags | CONTEXT_REGISTER_PACKAGE);
        if (pi != null) {
            final boolean restricted = (flags & CONTEXT_RESTRICTED) == CONTEXT_RESTRICTED;
            ContextImpl c = new ContextImpl(this, mMainThread, pi, mActivityToken,
                    new UserHandle(UserHandle.getUserId(application.uid)), restricted,
                    mDisplay, null, Display.INVALID_DISPLAY);
            if (c.mResources != null) {
                return c;
            }
        }

        throw new PackageManager.NameNotFoundException(
                "Application package " + application.packageName + " not found");
    }

    @Override
    public Context createPackageContext(String packageName, int flags)
            throws NameNotFoundException {
        return createPackageContextAsUser(packageName, flags,
                mUser != null ? mUser : Process.myUserHandle());
    }

    @Override
    public Context createPackageContextAsUser(String packageName, int flags, UserHandle user)
            throws NameNotFoundException {
        final boolean restricted = (flags & CONTEXT_RESTRICTED) == CONTEXT_RESTRICTED;
        if (packageName.equals("system") || packageName.equals("android")) {
            return new ContextImpl(this, mMainThread, mPackageInfo, mActivityToken,
                    user, restricted, mDisplay, null, Display.INVALID_DISPLAY);
        }

        LoadedApk pi = mMainThread.getPackageInfo(packageName, mResources.getCompatibilityInfo(),
                flags | CONTEXT_REGISTER_PACKAGE, user.getIdentifier());
        if (pi != null) {
            ContextImpl c = new ContextImpl(this, mMainThread, pi, mActivityToken,
                    user, restricted, mDisplay, null, Display.INVALID_DISPLAY);
            if (c.mResources != null) {
                return c;
            }
        }

        // Should be a better exception.
        throw new PackageManager.NameNotFoundException(
                "Application package " + packageName + " not found");
    }

    @Override
    public Context createConfigurationContext(Configuration overrideConfiguration) {
        if (overrideConfiguration == null) {
            throw new IllegalArgumentException("overrideConfiguration must not be null");
        }

        return new ContextImpl(this, mMainThread, mPackageInfo, mActivityToken,
                mUser, mRestricted, mDisplay, overrideConfiguration, Display.INVALID_DISPLAY);
    }

    @Override
    public Context createDisplayContext(Display display) {
        if (display == null) {
            throw new IllegalArgumentException("display must not be null");
        }

        return new ContextImpl(this, mMainThread, mPackageInfo, mActivityToken,
                mUser, mRestricted, display, null, Display.INVALID_DISPLAY);
    }

    Display getDisplay() {
        if (mDisplay != null) {
            return mDisplay;
        }
        return ResourcesManager.getInstance().getAdjustedDisplay(
                Display.DEFAULT_DISPLAY, mDisplayAdjustments);
    }

    private int getDisplayId() {
        return mDisplay != null ? mDisplay.getDisplayId() : Display.DEFAULT_DISPLAY;
    }

    @Override
    public boolean isRestricted() {
        return mRestricted;
    }

    @Override
    public DisplayAdjustments getDisplayAdjustments(int displayId) {
        return mDisplayAdjustments;
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

    /** {@hide} */
    @Override
    public int getUserId() {
        return mUser.getIdentifier();
    }

    static ContextImpl createSystemContext(ActivityThread mainThread) {
        LoadedApk packageInfo = new LoadedApk(mainThread);
        ContextImpl context = new ContextImpl(null, mainThread,
                packageInfo, null, null, false, null, null, Display.INVALID_DISPLAY);
        context.mResources.updateConfiguration(context.mResourcesManager.getConfiguration(),
                context.mResourcesManager.getDisplayMetricsLocked());
        return context;
    }

    static ContextImpl createAppContext(ActivityThread mainThread, LoadedApk packageInfo) {
        if (packageInfo == null) throw new IllegalArgumentException("packageInfo");
        return new ContextImpl(null, mainThread,
                packageInfo, null, null, false, null, null, Display.INVALID_DISPLAY);
    }

    static ContextImpl createActivityContext(ActivityThread mainThread,
            LoadedApk packageInfo, int displayId, Configuration overrideConfiguration) {
        if (packageInfo == null) throw new IllegalArgumentException("packageInfo");
        return new ContextImpl(null, mainThread, packageInfo, null, null, false,
                null, overrideConfiguration, displayId);
    }

    private ContextImpl(ContextImpl container, ActivityThread mainThread,
            LoadedApk packageInfo, IBinder activityToken, UserHandle user, boolean restricted,
            Display display, Configuration overrideConfiguration, int createDisplayWithId) {
        mOuterContext = this;

        mMainThread = mainThread;
        mActivityToken = activityToken;
        mRestricted = restricted;

        if (user == null) {
            user = Process.myUserHandle();
        }
        mUser = user;

        mPackageInfo = packageInfo;
        mResourcesManager = ResourcesManager.getInstance();

        final int displayId = (createDisplayWithId != Display.INVALID_DISPLAY)
                ? createDisplayWithId
                : (display != null) ? display.getDisplayId() : Display.DEFAULT_DISPLAY;

        CompatibilityInfo compatInfo = null;
        if (container != null) {
            compatInfo = container.getDisplayAdjustments(displayId).getCompatibilityInfo();
        }
        if (compatInfo == null) {
            compatInfo = (displayId == Display.DEFAULT_DISPLAY)
                    ? packageInfo.getCompatibilityInfo()
                    : CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO;
        }
        mDisplayAdjustments.setCompatibilityInfo(compatInfo);
        mDisplayAdjustments.setConfiguration(overrideConfiguration);

        mDisplay = (createDisplayWithId == Display.INVALID_DISPLAY) ? display
                : ResourcesManager.getInstance().getAdjustedDisplay(displayId, mDisplayAdjustments);

        Resources resources = packageInfo.getResources(mainThread);
        if (resources != null) {
            if (displayId != Display.DEFAULT_DISPLAY
                    || overrideConfiguration != null
                    || (compatInfo != null && compatInfo.applicationScale
                            != resources.getCompatibilityInfo().applicationScale)) {
                resources = mResourcesManager.getTopLevelResources(packageInfo.getResDir(),
                        packageInfo.getSplitResDirs(), packageInfo.getOverlayDirs(),
                        packageInfo.getApplicationInfo().sharedLibraryFiles, displayId,
                        overrideConfiguration, compatInfo);
            }
        }
        mResources = resources;

        if (container != null) {
            mBasePackageName = container.mBasePackageName;
            mOpPackageName = container.mOpPackageName;
        } else {
            mBasePackageName = packageInfo.mPackageName;
            ApplicationInfo ainfo = packageInfo.getApplicationInfo();
            if (ainfo.uid == Process.SYSTEM_UID && ainfo.uid != Process.myUid()) {
                // Special case: system components allow themselves to be loaded in to other
                // processes.  For purposes of app ops, we must then consider the context as
                // belonging to the package of this process, not the system itself, otherwise
                // the package+uid verifications in app ops will fail.
                mOpPackageName = ActivityThread.currentPackageName();
            } else {
                mOpPackageName = mBasePackageName;
            }
        }

        mContentResolver = new ApplicationContentResolver(this, mainThread, user);
    }

    void installSystemApplicationInfo(ApplicationInfo info, ClassLoader classLoader) {
        mPackageInfo.installSystemApplicationInfo(info, classLoader);
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

    final void setOuterContext(Context context) {
        mOuterContext = context;
    }

    final Context getOuterContext() {
        return mOuterContext;
    }

    final IBinder getActivityToken() {
        return mActivityToken;
    }

    @SuppressWarnings("deprecation")
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

    /**
     * Ensure that given directories exist, trying to create them if missing. If
     * unable to create, they are filtered by replacing with {@code null}.
     */
    private File[] ensureDirsExistOrFilter(File[] dirs) {
        File[] result = new File[dirs.length];
        for (int i = 0; i < dirs.length; i++) {
            File dir = dirs[i];
            if (!dir.exists()) {
                if (!dir.mkdirs()) {
                    // recheck existence in case of cross-process race
                    if (!dir.exists()) {
                        // Failing to mkdir() may be okay, since we might not have
                        // enough permissions; ask vold to create on our behalf.
                        final IMountService mount = IMountService.Stub.asInterface(
                                ServiceManager.getService("mount"));
                        try {
                            final int res = mount.mkdirs(getPackageName(), dir.getAbsolutePath());
                            if (res != 0) {
                                Log.w(TAG, "Failed to ensure " + dir + ": " + res);
                                dir = null;
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "Failed to ensure " + dir + ": " + e);
                            dir = null;
                        }
                    }
                }
            }
            result[i] = dir;
        }
        return result;
    }

    // ----------------------------------------------------------------------
    // ----------------------------------------------------------------------
    // ----------------------------------------------------------------------

    private static final class ApplicationContentResolver extends ContentResolver {
        private final ActivityThread mMainThread;
        private final UserHandle mUser;

        public ApplicationContentResolver(
                Context context, ActivityThread mainThread, UserHandle user) {
            super(context);
            mMainThread = Preconditions.checkNotNull(mainThread);
            mUser = Preconditions.checkNotNull(user);
        }

        @Override
        protected IContentProvider acquireProvider(Context context, String auth) {
            return mMainThread.acquireProvider(context,
                    ContentProvider.getAuthorityWithoutUserId(auth),
                    resolveUserIdFromAuthority(auth), true);
        }

        @Override
        protected IContentProvider acquireExistingProvider(Context context, String auth) {
            return mMainThread.acquireExistingProvider(context,
                    ContentProvider.getAuthorityWithoutUserId(auth),
                    resolveUserIdFromAuthority(auth), true);
        }

        @Override
        public boolean releaseProvider(IContentProvider provider) {
            return mMainThread.releaseProvider(provider, true);
        }

        @Override
        protected IContentProvider acquireUnstableProvider(Context c, String auth) {
            return mMainThread.acquireProvider(c,
                    ContentProvider.getAuthorityWithoutUserId(auth),
                    resolveUserIdFromAuthority(auth), false);
        }

        @Override
        public boolean releaseUnstableProvider(IContentProvider icp) {
            return mMainThread.releaseProvider(icp, false);
        }

        @Override
        public void unstableProviderDied(IContentProvider icp) {
            mMainThread.handleUnstableProviderDied(icp.asBinder(), true);
        }

        @Override
        public void appNotRespondingViaProvider(IContentProvider icp) {
            mMainThread.appNotRespondingViaProvider(icp.asBinder());
        }

        /** @hide */
        protected int resolveUserIdFromAuthority(String auth) {
            return ContentProvider.getUserIdFromAuthority(auth, mUser.getIdentifier());
        }
    }
}
