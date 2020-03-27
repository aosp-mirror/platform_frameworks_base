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

import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.StrictMode.vmIncorrectContextUseEnabled;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.AutofillOptions;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentCaptureOptions;
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
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.AssetManager;
import android.content.res.CompatResources;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.loader.ResourcesLoader;
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
import android.os.StrictMode;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.StorageManager;
import android.permission.IPermissionManager;
import android.permission.PermissionManager;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.text.TextUtils;
import android.util.AndroidRuntimeException;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Slog;
import android.view.Display;
import android.view.DisplayAdjustments;
import android.view.autofill.AutofillManager.AutofillClient;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;

import dalvik.system.BlockGuard;

import libcore.io.Memory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

class ReceiverRestrictedContext extends ContextWrapper {
    @UnsupportedAppUsage
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
    public Intent registerReceiverForAllUsers(BroadcastReceiver receiver, IntentFilter filter,
            String broadcastPermission, Handler scheduler) {
        return registerReceiverAsUser(
                receiver, UserHandle.ALL, filter, broadcastPermission, scheduler);
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

    @Override
    public boolean bindService(
          Intent service, int flags, Executor executor, ServiceConnection conn) {
        throw new ReceiverCallNotAllowedException(
            "BroadcastReceiver components are not allowed to bind to services");
    }

    @Override
    public boolean bindIsolatedService(Intent service, int flags, String instanceName,
            Executor executor, ServiceConnection conn) {
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

    private static final String XATTR_INODE_CACHE = "user.inode_cache";
    private static final String XATTR_INODE_CODE_CACHE = "user.inode_code_cache";

    /**
     * Map from package name, to preference name, to cached preferences.
     */
    @GuardedBy("ContextImpl.class")
    @UnsupportedAppUsage
    private static ArrayMap<String, ArrayMap<File, SharedPreferencesImpl>> sSharedPrefsCache;

    /**
     * Map from preference name to generated path.
     */
    @GuardedBy("ContextImpl.class")
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private ArrayMap<String, File> mSharedPrefsPaths;

    @UnsupportedAppUsage
    final @NonNull ActivityThread mMainThread;
    @UnsupportedAppUsage
    final @NonNull LoadedApk mPackageInfo;
    @UnsupportedAppUsage
    private @Nullable ClassLoader mClassLoader;

    private final @Nullable IBinder mToken;

    private final @NonNull UserHandle mUser;

    @UnsupportedAppUsage
    private final ApplicationContentResolver mContentResolver;

    @UnsupportedAppUsage
    private final String mBasePackageName;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private final String mOpPackageName;

    /** Attribution tag of this context */
    private final @Nullable String mAttributionTag;

    private final @NonNull ResourcesManager mResourcesManager;
    @UnsupportedAppUsage
    private @NonNull Resources mResources;
    private @Nullable Display mDisplay; // may be null if invalid display or not initialized yet.

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private final int mFlags;

    @UnsupportedAppUsage
    private Context mOuterContext;
    @UnsupportedAppUsage
    private int mThemeResource = 0;
    @UnsupportedAppUsage
    private Resources.Theme mTheme = null;
    @UnsupportedAppUsage
    private PackageManager mPackageManager;
    private Context mReceiverRestrictedContext = null;

    // The name of the split this Context is representing. May be null.
    private @Nullable String mSplitName = null;

    private @Nullable AutofillClient mAutofillClient = null;
    private @Nullable AutofillOptions mAutofillOptions;

    private ContentCaptureOptions mContentCaptureOptions = null;

    private final Object mSync = new Object();

    private boolean mIsSystemOrSystemUiContext;
    private boolean mIsUiContext;
    private boolean mIsAssociatedWithDisplay;

    @GuardedBy("mSync")
    private File mDatabasesDir;
    @GuardedBy("mSync")
    @UnsupportedAppUsage
    private File mPreferencesDir;
    @GuardedBy("mSync")
    private File mFilesDir;
    @GuardedBy("mSync")
    private File mCratesDir;
    @GuardedBy("mSync")
    private File mNoBackupFilesDir;
    @GuardedBy("mSync")
    private File mCacheDir;
    @GuardedBy("mSync")
    private File mCodeCacheDir;

    // The system service cache for the system services that are cached per-ContextImpl.
    @UnsupportedAppUsage
    final Object[] mServiceCache = SystemServiceRegistry.createServiceCache();

    static final int STATE_UNINITIALIZED = 0;
    static final int STATE_INITIALIZING = 1;
    static final int STATE_READY = 2;
    static final int STATE_NOT_FOUND = 3;

    /** @hide */
    @IntDef(prefix = { "STATE_" }, value = {
            STATE_UNINITIALIZED,
            STATE_INITIALIZING,
            STATE_READY,
            STATE_NOT_FOUND,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface ServiceInitializationState {}

    /**
     * Initialization state for each service. Any of {@link #STATE_UNINITIALIZED},
     * {@link #STATE_INITIALIZING} or {@link #STATE_READY},
     */
    @ServiceInitializationState
    final int[] mServiceInitializationStateArray = new int[mServiceCache.length];

    @UnsupportedAppUsage
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

        final IPackageManager pm = ActivityThread.getPackageManager();
        final IPermissionManager permissionManager = ActivityThread.getPermissionManager();
        if (pm != null && permissionManager != null) {
            // Doesn't matter if we make more than one instance.
            return (mPackageManager = new ApplicationPackageManager(this, pm, permissionManager));
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
    public Executor getMainExecutor() {
        return mMainThread.getExecutor();
    }

    @Override
    public Context getApplicationContext() {
        return (mPackageInfo != null) ?
                mPackageInfo.getApplication() : mMainThread.getApplication();
    }

    @Override
    public void setTheme(int resId) {
        synchronized (mSync) {
            if (mThemeResource != resId) {
                mThemeResource = resId;
                initializeTheme();
            }
        }
    }

    @Override
    public int getThemeResId() {
        synchronized (mSync) {
            return mThemeResource;
        }
    }

    @Override
    public Resources.Theme getTheme() {
        synchronized (mSync) {
            if (mTheme != null) {
                return mTheme;
            }

            mThemeResource = Resources.selectDefaultTheme(mThemeResource,
                    getOuterContext().getApplicationInfo().targetSdkVersion);
            initializeTheme();

            return mTheme;
        }
    }

    private void initializeTheme() {
        if (mTheme == null) {
            mTheme = mResources.newTheme();
        }
        mTheme.applyStyle(mThemeResource, true);
    }

    @Override
    public ClassLoader getClassLoader() {
        return mClassLoader != null ? mClassLoader : (mPackageInfo != null ? mPackageInfo.getClassLoader() : ClassLoader.getSystemClassLoader());
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

    /** @hide */
    @Override
    public @Nullable String getAttributionTag() {
        return mAttributionTag;
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
    public SharedPreferences getSharedPreferences(String name, int mode) {
        // At least one application in the world actually passes in a null
        // name.  This happened to work because when we generated the file name
        // we would stringify it to "null.xml".  Nice.
        if (mPackageInfo.getApplicationInfo().targetSdkVersion <
                Build.VERSION_CODES.KITKAT) {
            if (name == null) {
                name = "null";
            }
        }

        File file;
        synchronized (ContextImpl.class) {
            if (mSharedPrefsPaths == null) {
                mSharedPrefsPaths = new ArrayMap<>();
            }
            file = mSharedPrefsPaths.get(name);
            if (file == null) {
                file = getSharedPreferencesPath(name);
                mSharedPrefsPaths.put(name, file);
            }
        }
        return getSharedPreferences(file, mode);
    }

    @Override
    public SharedPreferences getSharedPreferences(File file, int mode) {
        SharedPreferencesImpl sp;
        synchronized (ContextImpl.class) {
            final ArrayMap<File, SharedPreferencesImpl> cache = getSharedPreferencesCacheLocked();
            sp = cache.get(file);
            if (sp == null) {
                checkMode(mode);
                if (getApplicationInfo().targetSdkVersion >= android.os.Build.VERSION_CODES.O) {
                    if (isCredentialProtectedStorage()
                            && !getSystemService(UserManager.class)
                                    .isUserUnlockingOrUnlocked(UserHandle.myUserId())) {
                        throw new IllegalStateException("SharedPreferences in credential encrypted "
                                + "storage are not available until after user is unlocked");
                    }
                }
                sp = new SharedPreferencesImpl(file, mode);
                cache.put(file, sp);
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

    @GuardedBy("ContextImpl.class")
    private ArrayMap<File, SharedPreferencesImpl> getSharedPreferencesCacheLocked() {
        if (sSharedPrefsCache == null) {
            sSharedPrefsCache = new ArrayMap<>();
        }

        final String packageName = getPackageName();
        ArrayMap<File, SharedPreferencesImpl> packagePrefs = sSharedPrefsCache.get(packageName);
        if (packagePrefs == null) {
            packagePrefs = new ArrayMap<>();
            sSharedPrefsCache.put(packageName, packagePrefs);
        }

        return packagePrefs;
    }

    @Override
    public void reloadSharedPreferences() {
        // Build the list of all per-context impls (i.e. caches) we know about
        ArrayList<SharedPreferencesImpl> spImpls = new ArrayList<>();
        synchronized (ContextImpl.class) {
            final ArrayMap<File, SharedPreferencesImpl> cache = getSharedPreferencesCacheLocked();
            for (int i = 0; i < cache.size(); i++) {
                final SharedPreferencesImpl sp = cache.valueAt(i);
                if (sp != null) {
                    spImpls.add(sp);
                }
            }
        }

        // Issue the reload outside the cache lock
        for (int i = 0; i < spImpls.size(); i++) {
            spImpls.get(i).startReloadIfChangedUnexpectedly();
        }
    }

    /**
     * Try our best to migrate all files from source to target that match
     * requested prefix.
     *
     * @return the number of files moved, or -1 if there was trouble.
     */
    private static int moveFiles(File sourceDir, File targetDir, final String prefix) {
        final File[] sourceFiles = FileUtils.listFilesOrEmpty(sourceDir, new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.startsWith(prefix);
            }
        });

        int res = 0;
        for (File sourceFile : sourceFiles) {
            final File targetFile = new File(targetDir, sourceFile.getName());
            Log.d(TAG, "Migrating " + sourceFile + " to " + targetFile);
            try {
                FileUtils.copyFileOrThrow(sourceFile, targetFile);
                FileUtils.copyPermissions(sourceFile, targetFile);
                if (!sourceFile.delete()) {
                    throw new IOException("Failed to clean up " + sourceFile);
                }
                if (res != -1) {
                    res++;
                }
            } catch (IOException e) {
                Log.w(TAG, "Failed to migrate " + sourceFile + ": " + e);
                res = -1;
            }
        }
        return res;
    }

    @Override
    public boolean moveSharedPreferencesFrom(Context sourceContext, String name) {
        synchronized (ContextImpl.class) {
            final File source = sourceContext.getSharedPreferencesPath(name);
            final File target = getSharedPreferencesPath(name);

            final int res = moveFiles(source.getParentFile(), target.getParentFile(),
                    source.getName());
            if (res > 0) {
                // We moved at least one file, so evict any in-memory caches for
                // either location
                final ArrayMap<File, SharedPreferencesImpl> cache =
                        getSharedPreferencesCacheLocked();
                cache.remove(source);
                cache.remove(target);
            }
            return res != -1;
        }
    }

    @Override
    public boolean deleteSharedPreferences(String name) {
        synchronized (ContextImpl.class) {
            final File prefs = getSharedPreferencesPath(name);
            final File prefsBackup = SharedPreferencesImpl.makeBackupFile(prefs);

            // Evict any in-memory caches
            final ArrayMap<File, SharedPreferencesImpl> cache = getSharedPreferencesCacheLocked();
            cache.remove(prefs);

            prefs.delete();
            prefsBackup.delete();

            // We failed if files are still lingering
            return !(prefs.exists() || prefsBackup.exists());
        }
    }

    @UnsupportedAppUsage
    private File getPreferencesDir() {
        synchronized (mSync) {
            if (mPreferencesDir == null) {
                mPreferencesDir = new File(getDataDir(), "shared_prefs");
            }
            return ensurePrivateDirExists(mPreferencesDir);
        }
    }

    @Override
    public FileInputStream openFileInput(String name)
        throws FileNotFoundException {
        File f = makeFilename(getFilesDir(), name);
        return new FileInputStream(f);
    }

    @Override
    public FileOutputStream openFileOutput(String name, int mode) throws FileNotFoundException {
        checkMode(mode);
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

    /**
     * Common-path handling of app data dir creation
     */
    private static File ensurePrivateDirExists(File file) {
        return ensurePrivateDirExists(file, 0771, -1, null);
    }

    private static File ensurePrivateCacheDirExists(File file, String xattr) {
        final int gid = UserHandle.getCacheAppGid(Process.myUid());
        return ensurePrivateDirExists(file, 02771, gid, xattr);
    }

    private static File ensurePrivateDirExists(File file, int mode, int gid, String xattr) {
        if (!file.exists()) {
            final String path = file.getAbsolutePath();
            try {
                Os.mkdir(path, mode);
                Os.chmod(path, mode);
                if (gid != -1) {
                    Os.chown(path, -1, gid);
                }
            } catch (ErrnoException e) {
                if (e.errno == OsConstants.EEXIST) {
                    // We must have raced with someone; that's okay
                } else {
                    Log.w(TAG, "Failed to ensure " + file + ": " + e.getMessage());
                }
            }

            if (xattr != null) {
                try {
                    final StructStat stat = Os.stat(file.getAbsolutePath());
                    final byte[] value = new byte[8];
                    Memory.pokeLong(value, 0, stat.st_ino, ByteOrder.nativeOrder());
                    Os.setxattr(file.getParentFile().getAbsolutePath(), xattr, value, 0);
                } catch (ErrnoException e) {
                    Log.w(TAG, "Failed to update " + xattr + ": " + e.getMessage());
                }
            }
        }
        return file;
    }

    @Override
    public File getFilesDir() {
        synchronized (mSync) {
            if (mFilesDir == null) {
                mFilesDir = new File(getDataDir(), "files");
            }
            return ensurePrivateDirExists(mFilesDir);
        }
    }

    @Override
    public File getCrateDir(@NonNull String crateId) {
        Preconditions.checkArgument(FileUtils.isValidExtFilename(crateId), "invalidated crateId");
        final Path cratesRootPath = getDataDir().toPath().resolve("crates");
        final Path absoluteNormalizedCratePath = cratesRootPath.resolve(crateId)
                .toAbsolutePath().normalize();

        synchronized (mSync) {
            if (mCratesDir == null) {
                mCratesDir = cratesRootPath.toFile();
            }
            ensurePrivateDirExists(mCratesDir);
        }

        File cratedDir = absoluteNormalizedCratePath.toFile();
        return ensurePrivateDirExists(cratedDir);
    }

    @Override
    public File getNoBackupFilesDir() {
        synchronized (mSync) {
            if (mNoBackupFilesDir == null) {
                mNoBackupFilesDir = new File(getDataDir(), "no_backup");
            }
            return ensurePrivateDirExists(mNoBackupFilesDir);
        }
    }

    @Override
    public File getExternalFilesDir(String type) {
        // Operates on primary external storage
        final File[] dirs = getExternalFilesDirs(type);
        return (dirs != null && dirs.length > 0) ? dirs[0] : null;
    }

    @Override
    public File[] getExternalFilesDirs(String type) {
        synchronized (mSync) {
            File[] dirs = Environment.buildExternalStorageAppFilesDirs(getPackageName());
            if (type != null) {
                dirs = Environment.buildPaths(dirs, type);
            }
            return ensureExternalDirsExistOrFilter(dirs, true /* tryCreateInProcess */);
        }
    }

    @Override
    public File getObbDir() {
        // Operates on primary external storage
        final File[] dirs = getObbDirs();
        return (dirs != null && dirs.length > 0) ? dirs[0] : null;
    }

    @Override
    public File[] getObbDirs() {
        synchronized (mSync) {
            File[] dirs = Environment.buildExternalStorageAppObbDirs(getPackageName());
            return ensureExternalDirsExistOrFilter(dirs, true /* tryCreateInProcess */);
        }
    }

    @Override
    public File getCacheDir() {
        synchronized (mSync) {
            if (mCacheDir == null) {
                mCacheDir = new File(getDataDir(), "cache");
            }
            return ensurePrivateCacheDirExists(mCacheDir, XATTR_INODE_CACHE);
        }
    }

    @Override
    public File getCodeCacheDir() {
        synchronized (mSync) {
            if (mCodeCacheDir == null) {
                mCodeCacheDir = getCodeCacheDirBeforeBind(getDataDir());
            }
            return ensurePrivateCacheDirExists(mCodeCacheDir, XATTR_INODE_CODE_CACHE);
        }
    }

    /**
     * Helper for getting code-cache dir potentially before application bind.
     *
     * @hide
     */
    static File getCodeCacheDirBeforeBind(File dataDir) {
        return new File(dataDir, "code_cache");
    }

    @Override
    public File getExternalCacheDir() {
        // Operates on primary external storage
        final File[] dirs = getExternalCacheDirs();
        return (dirs != null && dirs.length > 0) ? dirs[0] : null;
    }

    @Override
    public File[] getExternalCacheDirs() {
        synchronized (mSync) {
            File[] dirs = Environment.buildExternalStorageAppCacheDirs(getPackageName());
            // We don't try to create cache directories in-process, because they need special
            // setup for accurate quota tracking. This ensures the cache dirs are always
            // created through StorageManagerService.
            return ensureExternalDirsExistOrFilter(dirs, false /* tryCreateInProcess */);
        }
    }

    @Override
    public File[] getExternalMediaDirs() {
        synchronized (mSync) {
            File[] dirs = Environment.buildExternalStorageAppMediaDirs(getPackageName());
            return ensureExternalDirsExistOrFilter(dirs, true /* tryCreateInProcess */);
        }
    }

    /**
     * @hide
     */
    @Nullable
    @Override
    public File getPreloadsFileCache() {
        return Environment.getDataPreloadsFileCacheDirectory(getPackageName());
    }

    @Override
    public File getFileStreamPath(String name) {
        return makeFilename(getFilesDir(), name);
    }

    @Override
    public File getSharedPreferencesPath(String name) {
        return makeFilename(getPreferencesDir(), name + ".xml");
    }

    @Override
    public String[] fileList() {
        return FileUtils.listOrEmpty(getFilesDir());
    }

    @Override
    public SQLiteDatabase openOrCreateDatabase(String name, int mode, CursorFactory factory) {
        return openOrCreateDatabase(name, mode, factory, null);
    }

    @Override
    public SQLiteDatabase openOrCreateDatabase(String name, int mode, CursorFactory factory,
            DatabaseErrorHandler errorHandler) {
        checkMode(mode);
        File f = getDatabasePath(name);
        int flags = SQLiteDatabase.CREATE_IF_NECESSARY;
        if ((mode & MODE_ENABLE_WRITE_AHEAD_LOGGING) != 0) {
            flags |= SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING;
        }
        if ((mode & MODE_NO_LOCALIZED_COLLATORS) != 0) {
            flags |= SQLiteDatabase.NO_LOCALIZED_COLLATORS;
        }
        SQLiteDatabase db = SQLiteDatabase.openDatabase(f.getPath(), factory, flags, errorHandler);
        setFilePermissionsFromMode(f.getPath(), mode, 0);
        return db;
    }

    @Override
    public boolean moveDatabaseFrom(Context sourceContext, String name) {
        synchronized (ContextImpl.class) {
            final File source = sourceContext.getDatabasePath(name);
            final File target = getDatabasePath(name);
            return moveFiles(source.getParentFile(), target.getParentFile(),
                    source.getName()) != -1;
        }
    }

    @Override
    public boolean deleteDatabase(String name) {
        try {
            File f = getDatabasePath(name);
            return SQLiteDatabase.deleteDatabase(f);
        } catch (Exception e) {
        }
        return false;
    }

    @Override
    public File getDatabasePath(String name) {
        File dir;
        File f;

        if (name.charAt(0) == File.separatorChar) {
            String dirPath = name.substring(0, name.lastIndexOf(File.separatorChar));
            dir = new File(dirPath);
            name = name.substring(name.lastIndexOf(File.separatorChar));
            f = new File(dir, name);

            if (!dir.isDirectory() && dir.mkdir()) {
                FileUtils.setPermissions(dir.getPath(),
                    FileUtils.S_IRWXU|FileUtils.S_IRWXG|FileUtils.S_IXOTH,
                    -1, -1);
            }
        } else {
            dir = getDatabasesDir();
            f = makeFilename(dir, name);
        }

        return f;
    }

    @Override
    public String[] databaseList() {
        return FileUtils.listOrEmpty(getDatabasesDir());
    }

    private File getDatabasesDir() {
        synchronized (mSync) {
            if (mDatabasesDir == null) {
                if ("android".equals(getPackageName())) {
                    mDatabasesDir = new File("/data/system");
                } else {
                    mDatabasesDir = new File(getDataDir(), "databases");
                }
            }
            return ensurePrivateDirExists(mDatabasesDir);
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

        // Calling start activity from outside an activity without FLAG_ACTIVITY_NEW_TASK is
        // generally not allowed, except if the caller specifies the task id the activity should
        // be launched in. A bug was existed between N and O-MR1 which allowed this to work. We
        // maintain this for backwards compatibility.
        final int targetSdkVersion = getApplicationInfo().targetSdkVersion;

        if ((intent.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) == 0
                && (targetSdkVersion < Build.VERSION_CODES.N
                        || targetSdkVersion >= Build.VERSION_CODES.P)
                && (options == null
                        || ActivityOptions.fromBundle(options).getLaunchTaskId() == -1)) {
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
            ActivityTaskManager.getService().startActivityAsUser(
                    mMainThread.getApplicationThread(), getBasePackageName(), getAttributionTag(),
                    intent, intent.resolveTypeIfNeeded(getContentResolver()),
                    null, null, 0, Intent.FLAG_ACTIVITY_NEW_TASK, null, options,
                    user.getIdentifier());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void startActivities(Intent[] intents) {
        warnIfCallingFromSystemProcess();
        startActivities(intents, null);
    }

    /** @hide */
    @Override
    public int startActivitiesAsUser(Intent[] intents, Bundle options, UserHandle userHandle) {
        if ((intents[0].getFlags()&Intent.FLAG_ACTIVITY_NEW_TASK) == 0) {
            throw new AndroidRuntimeException(
                    "Calling startActivities() from outside of an Activity "
                    + " context requires the FLAG_ACTIVITY_NEW_TASK flag on first Intent."
                    + " Is this really what you want?");
        }
        return mMainThread.getInstrumentation().execStartActivitiesAsUser(
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
                fillInIntent.prepareToLeaveProcess(this);
                resolvedType = fillInIntent.resolveTypeIfNeeded(getContentResolver());
            }
            int result = ActivityTaskManager.getService()
                .startActivityIntentSender(mMainThread.getApplicationThread(),
                        intent != null ? intent.getTarget() : null,
                        intent != null ? intent.getWhitelistToken() : null,
                        fillInIntent, resolvedType, null, null,
                        0, flagsMask, flagsValues, options);
            if (result == ActivityManager.START_CANCELED) {
                throw new IntentSender.SendIntentException();
            }
            Instrumentation.checkStartActivityResult(result, null);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void sendBroadcast(Intent intent) {
        warnIfCallingFromSystemProcess();
        String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
        try {
            intent.prepareToLeaveProcess(this);
            ActivityManager.getService().broadcastIntentWithFeature(
                    mMainThread.getApplicationThread(), getAttributionTag(), intent, resolvedType,
                    null, Activity.RESULT_OK, null, null, null, AppOpsManager.OP_NONE, null, false,
                    false, getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void sendBroadcast(Intent intent, String receiverPermission) {
        warnIfCallingFromSystemProcess();
        String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
        String[] receiverPermissions = receiverPermission == null ? null
                : new String[] {receiverPermission};
        try {
            intent.prepareToLeaveProcess(this);
            ActivityManager.getService().broadcastIntentWithFeature(
                    mMainThread.getApplicationThread(), getAttributionTag(), intent, resolvedType,
                    null, Activity.RESULT_OK, null, null, receiverPermissions,
                    AppOpsManager.OP_NONE, null, false, false, getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void sendBroadcastMultiplePermissions(Intent intent, String[] receiverPermissions) {
        warnIfCallingFromSystemProcess();
        String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
        try {
            intent.prepareToLeaveProcess(this);
            ActivityManager.getService().broadcastIntentWithFeature(
                    mMainThread.getApplicationThread(), getAttributionTag(), intent, resolvedType,
                    null, Activity.RESULT_OK, null, null, receiverPermissions,
                    AppOpsManager.OP_NONE, null, false, false, getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void sendBroadcastAsUserMultiplePermissions(Intent intent, UserHandle user,
            String[] receiverPermissions) {
        String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
        try {
            intent.prepareToLeaveProcess(this);
            ActivityManager.getService().broadcastIntentWithFeature(
                    mMainThread.getApplicationThread(), getAttributionTag(), intent, resolvedType,
                    null, Activity.RESULT_OK, null, null, receiverPermissions,
                    AppOpsManager.OP_NONE, null, false, false, user.getIdentifier());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void sendBroadcast(Intent intent, String receiverPermission, Bundle options) {
        warnIfCallingFromSystemProcess();
        String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
        String[] receiverPermissions = receiverPermission == null ? null
                : new String[] {receiverPermission};
        try {
            intent.prepareToLeaveProcess(this);
            ActivityManager.getService().broadcastIntentWithFeature(
                    mMainThread.getApplicationThread(), getAttributionTag(), intent, resolvedType,
                    null, Activity.RESULT_OK, null, null, receiverPermissions,
                    AppOpsManager.OP_NONE, options, false, false, getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void sendBroadcast(Intent intent, String receiverPermission, int appOp) {
        warnIfCallingFromSystemProcess();
        String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
        String[] receiverPermissions = receiverPermission == null ? null
                : new String[] {receiverPermission};
        try {
            intent.prepareToLeaveProcess(this);
            ActivityManager.getService().broadcastIntentWithFeature(
                    mMainThread.getApplicationThread(), getAttributionTag(), intent, resolvedType,
                    null, Activity.RESULT_OK, null, null, receiverPermissions, appOp, null, false,
                    false, getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void sendOrderedBroadcast(Intent intent, String receiverPermission) {
        warnIfCallingFromSystemProcess();
        String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
        String[] receiverPermissions = receiverPermission == null ? null
                : new String[] {receiverPermission};
        try {
            intent.prepareToLeaveProcess(this);
            ActivityManager.getService().broadcastIntentWithFeature(
                    mMainThread.getApplicationThread(), getAttributionTag(), intent, resolvedType,
                    null, Activity.RESULT_OK, null, null, receiverPermissions,
                    AppOpsManager.OP_NONE, null, true, false, getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
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
            intent.prepareToLeaveProcess(this);
            ActivityManager.getService().broadcastIntentWithFeature(
                    mMainThread.getApplicationThread(), getAttributionTag(), intent, resolvedType,
                    rd, initialCode, initialData, initialExtras, receiverPermissions, appOp,
                    options, true, false, getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void sendBroadcastAsUser(Intent intent, UserHandle user) {
        String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
        try {
            intent.prepareToLeaveProcess(this);
            ActivityManager.getService().broadcastIntentWithFeature(
                    mMainThread.getApplicationThread(), getAttributionTag(), intent, resolvedType,
                    null, Activity.RESULT_OK, null, null, null, AppOpsManager.OP_NONE, null, false,
                    false, user.getIdentifier());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void sendBroadcastAsUser(Intent intent, UserHandle user,
            String receiverPermission) {
        sendBroadcastAsUser(intent, user, receiverPermission, AppOpsManager.OP_NONE);
    }

    @Override
    public void sendBroadcastAsUser(Intent intent, UserHandle user, String receiverPermission,
            Bundle options) {
        String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
        String[] receiverPermissions = receiverPermission == null ? null
                : new String[] {receiverPermission};
        try {
            intent.prepareToLeaveProcess(this);
            ActivityManager.getService().broadcastIntentWithFeature(
                    mMainThread.getApplicationThread(), getAttributionTag(), intent, resolvedType,
                    null, Activity.RESULT_OK, null, null, receiverPermissions,
                    AppOpsManager.OP_NONE, options, false, false, user.getIdentifier());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void sendBroadcastAsUser(Intent intent, UserHandle user,
            String receiverPermission, int appOp) {
        String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
        String[] receiverPermissions = receiverPermission == null ? null
                : new String[] {receiverPermission};
        try {
            intent.prepareToLeaveProcess(this);
            ActivityManager.getService().broadcastIntentWithFeature(
                    mMainThread.getApplicationThread(), getAttributionTag(), intent, resolvedType,
                    null, Activity.RESULT_OK, null, null, receiverPermissions, appOp, null, false,
                    false, user.getIdentifier());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
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
            intent.prepareToLeaveProcess(this);
            ActivityManager.getService().broadcastIntentWithFeature(
                    mMainThread.getApplicationThread(), getAttributionTag(), intent, resolvedType,
                    rd, initialCode, initialData, initialExtras, receiverPermissions, appOp,
                    options, true, false, user.getIdentifier());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void sendOrderedBroadcast(Intent intent, String receiverPermission,
            String receiverAppOp, BroadcastReceiver resultReceiver, Handler scheduler,
            int initialCode, String initialData, @Nullable Bundle initialExtras) {
        int intAppOp = AppOpsManager.OP_NONE;
        if (!TextUtils.isEmpty(receiverAppOp)) {
            intAppOp = AppOpsManager.strOpToOp(receiverAppOp);
        }
        sendOrderedBroadcastAsUser(intent, getUser(),
                receiverPermission, intAppOp, resultReceiver, scheduler, initialCode, initialData,
                initialExtras);
    }

    @Override
    public void sendOrderedBroadcast(Intent intent, int initialCode, String receiverPermission,
            String receiverAppOp, BroadcastReceiver resultReceiver, Handler scheduler,
            String initialData, @Nullable Bundle initialExtras, Bundle options) {
        int intAppOp = AppOpsManager.OP_NONE;
        if (!TextUtils.isEmpty(receiverAppOp)) {
            intAppOp = AppOpsManager.strOpToOp(receiverAppOp);
        }
        sendOrderedBroadcastAsUser(intent, getUser(), receiverPermission, intAppOp, options,
                resultReceiver, scheduler, initialCode, initialData, initialExtras);
    }

    @Override
    @Deprecated
    public void sendStickyBroadcast(Intent intent) {
        warnIfCallingFromSystemProcess();
        String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
        try {
            intent.prepareToLeaveProcess(this);
            ActivityManager.getService().broadcastIntentWithFeature(
                    mMainThread.getApplicationThread(), getAttributionTag(), intent, resolvedType,
                    null, Activity.RESULT_OK, null, null, null, AppOpsManager.OP_NONE, null, false,
                    true, getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
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
            intent.prepareToLeaveProcess(this);
            ActivityManager.getService().broadcastIntentWithFeature(
                    mMainThread.getApplicationThread(), getAttributionTag(), intent, resolvedType,
                    rd, initialCode, initialData, initialExtras, null, AppOpsManager.OP_NONE, null,
                    true, true, getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
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
            intent.prepareToLeaveProcess(this);
            ActivityManager.getService().unbroadcastIntent(
                    mMainThread.getApplicationThread(), intent, getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    @Deprecated
    public void sendStickyBroadcastAsUser(Intent intent, UserHandle user) {
        String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
        try {
            intent.prepareToLeaveProcess(this);
            ActivityManager.getService().broadcastIntentWithFeature(
                    mMainThread.getApplicationThread(), getAttributionTag(), intent, resolvedType,
                    null, Activity.RESULT_OK, null, null, null, AppOpsManager.OP_NONE, null, false,
                    true, user.getIdentifier());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    @Deprecated
    public void sendStickyBroadcastAsUser(Intent intent, UserHandle user, Bundle options) {
        String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
        try {
            intent.prepareToLeaveProcess(this);
            ActivityManager.getService().broadcastIntentWithFeature(
                    mMainThread.getApplicationThread(), getAttributionTag(), intent, resolvedType,
                    null, Activity.RESULT_OK, null, null, null, AppOpsManager.OP_NONE, options,
                    false, true, user.getIdentifier());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
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
            intent.prepareToLeaveProcess(this);
            ActivityManager.getService().broadcastIntentWithFeature(
                    mMainThread.getApplicationThread(), getAttributionTag(), intent, resolvedType,
                    rd, initialCode, initialData, initialExtras, null, AppOpsManager.OP_NONE, null,
                    true, true, user.getIdentifier());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
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
            intent.prepareToLeaveProcess(this);
            ActivityManager.getService().unbroadcastIntent(
                    mMainThread.getApplicationThread(), intent, user.getIdentifier());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        return registerReceiver(receiver, filter, null, null);
    }

    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
            int flags) {
        return registerReceiver(receiver, filter, null, null, flags);
    }

    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
            String broadcastPermission, Handler scheduler) {
        return registerReceiverInternal(receiver, getUserId(),
                filter, broadcastPermission, scheduler, getOuterContext(), 0);
    }

    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
            String broadcastPermission, Handler scheduler, int flags) {
        return registerReceiverInternal(receiver, getUserId(),
                filter, broadcastPermission, scheduler, getOuterContext(), flags);
    }

    @Override
    public Intent registerReceiverForAllUsers(BroadcastReceiver receiver,
            IntentFilter filter, String broadcastPermission, Handler scheduler) {
        return registerReceiverAsUser(receiver, UserHandle.ALL,
                filter, broadcastPermission, scheduler);
    }

    @Override
    public Intent registerReceiverAsUser(BroadcastReceiver receiver, UserHandle user,
            IntentFilter filter, String broadcastPermission, Handler scheduler) {
        return registerReceiverInternal(receiver, user.getIdentifier(),
                filter, broadcastPermission, scheduler, getOuterContext(), 0);
    }

    private Intent registerReceiverInternal(BroadcastReceiver receiver, int userId,
            IntentFilter filter, String broadcastPermission,
            Handler scheduler, Context context, int flags) {
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
            final Intent intent = ActivityManager.getService().registerReceiverWithFeature(
                    mMainThread.getApplicationThread(), mBasePackageName, getAttributionTag(), rd,
                    filter, broadcastPermission, userId, flags);
            if (intent != null) {
                intent.setExtrasClassLoader(getClassLoader());
                intent.prepareToEnterProcess();
            }
            return intent;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void unregisterReceiver(BroadcastReceiver receiver) {
        if (mPackageInfo != null) {
            IIntentReceiver rd = mPackageInfo.forgetReceiverDispatcher(
                    getOuterContext(), receiver);
            try {
                ActivityManager.getService().unregisterReceiver(rd);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
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
        return startServiceCommon(service, false, mUser);
    }

    @Override
    public ComponentName startForegroundService(Intent service) {
        warnIfCallingFromSystemProcess();
        return startServiceCommon(service, true, mUser);
    }

    @Override
    public boolean stopService(Intent service) {
        warnIfCallingFromSystemProcess();
        return stopServiceCommon(service, mUser);
    }

    @Override
    public ComponentName startServiceAsUser(Intent service, UserHandle user) {
        return startServiceCommon(service, false, user);
    }

    @Override
    public ComponentName startForegroundServiceAsUser(Intent service, UserHandle user) {
        return startServiceCommon(service, true, user);
    }

    private ComponentName startServiceCommon(Intent service, boolean requireForeground,
            UserHandle user) {
        try {
            validateServiceIntent(service);
            service.prepareToLeaveProcess(this);
            ComponentName cn = ActivityManager.getService().startService(
                    mMainThread.getApplicationThread(), service,
                    service.resolveTypeIfNeeded(getContentResolver()), requireForeground,
                    getOpPackageName(), getAttributionTag(), user.getIdentifier());
            if (cn != null) {
                if (cn.getPackageName().equals("!")) {
                    throw new SecurityException(
                            "Not allowed to start service " + service
                            + " without permission " + cn.getClassName());
                } else if (cn.getPackageName().equals("!!")) {
                    throw new SecurityException(
                            "Unable to start service " + service
                            + ": " + cn.getClassName());
                } else if (cn.getPackageName().equals("?")) {
                    throw new IllegalStateException(
                            "Not allowed to start service " + service + ": " + cn.getClassName());
                }
            }
            return cn;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public boolean stopServiceAsUser(Intent service, UserHandle user) {
        return stopServiceCommon(service, user);
    }

    private boolean stopServiceCommon(Intent service, UserHandle user) {
        try {
            validateServiceIntent(service);
            service.prepareToLeaveProcess(this);
            int res = ActivityManager.getService().stopService(
                mMainThread.getApplicationThread(), service,
                service.resolveTypeIfNeeded(getContentResolver()), user.getIdentifier());
            if (res < 0) {
                throw new SecurityException(
                        "Not allowed to stop service " + service);
            }
            return res != 0;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public boolean bindService(Intent service, ServiceConnection conn, int flags) {
        warnIfCallingFromSystemProcess();
        return bindServiceCommon(service, conn, flags, null, mMainThread.getHandler(), null,
                getUser());
    }

    @Override
    public boolean bindService(
            Intent service, int flags, Executor executor, ServiceConnection conn) {
        warnIfCallingFromSystemProcess();
        return bindServiceCommon(service, conn, flags, null, null, executor, getUser());
    }

    @Override
    public boolean bindIsolatedService(Intent service, int flags, String instanceName,
            Executor executor, ServiceConnection conn) {
        warnIfCallingFromSystemProcess();
        if (instanceName == null) {
            throw new NullPointerException("null instanceName");
        }
        return bindServiceCommon(service, conn, flags, instanceName, null, executor, getUser());
    }

    /** @hide */
    @Override
    public boolean bindServiceAsUser(Intent service, ServiceConnection conn, int flags,
            UserHandle user) {
        return bindServiceCommon(service, conn, flags, null, mMainThread.getHandler(), null, user);
    }

    /** @hide */
    @Override
    public boolean bindServiceAsUser(Intent service, ServiceConnection conn, int flags,
            Handler handler, UserHandle user) {
        if (handler == null) {
            throw new IllegalArgumentException("handler must not be null.");
        }
        return bindServiceCommon(service, conn, flags, null, handler, null, user);
    }

    /** @hide */
    @Override
    public IServiceConnection getServiceDispatcher(ServiceConnection conn, Handler handler,
            int flags) {
        return mPackageInfo.getServiceDispatcher(conn, getOuterContext(), handler, flags);
    }

    /** @hide */
    @Override
    public IApplicationThread getIApplicationThread() {
        return mMainThread.getApplicationThread();
    }

    /** @hide */
    @Override
    public Handler getMainThreadHandler() {
        return mMainThread.getHandler();
    }

    private boolean bindServiceCommon(Intent service, ServiceConnection conn, int flags,
            String instanceName, Handler handler, Executor executor, UserHandle user) {
        // Keep this in sync with DevicePolicyManager.bindDeviceAdminServiceAsUser.
        IServiceConnection sd;
        if (conn == null) {
            throw new IllegalArgumentException("connection is null");
        }
        if (handler != null && executor != null) {
            throw new IllegalArgumentException("Handler and Executor both supplied");
        }
        if (mPackageInfo != null) {
            if (executor != null) {
                sd = mPackageInfo.getServiceDispatcher(conn, getOuterContext(), executor, flags);
            } else {
                sd = mPackageInfo.getServiceDispatcher(conn, getOuterContext(), handler, flags);
            }
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
            service.prepareToLeaveProcess(this);
            int res = ActivityManager.getService().bindIsolatedService(
                mMainThread.getApplicationThread(), getActivityToken(), service,
                service.resolveTypeIfNeeded(getContentResolver()),
                sd, flags, instanceName, getOpPackageName(), user.getIdentifier());
            if (res < 0) {
                throw new SecurityException(
                        "Not allowed to bind to service " + service);
            }
            return res != 0;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void updateServiceGroup(@NonNull ServiceConnection conn, int group, int importance) {
        if (conn == null) {
            throw new IllegalArgumentException("connection is null");
        }
        if (mPackageInfo != null) {
            IServiceConnection sd = mPackageInfo.lookupServiceDispatcher(conn, getOuterContext());
            if (sd == null) {
                throw new IllegalArgumentException("ServiceConnection not currently bound: "
                        + conn);
            }
            try {
                ActivityManager.getService().updateServiceGroup(sd, group, importance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        } else {
            throw new RuntimeException("Not supported in system context");
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
                ActivityManager.getService().unbindService(sd);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
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
            return ActivityManager.getService().startInstrumentation(
                    className, profileFile, 0, arguments, null, null, getUserId(),
                    null /* ABI override */);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public Object getSystemService(String name) {
        // Check incorrect Context usage.
        if (isUiComponent(name) && !isUiContext() && vmIncorrectContextUseEnabled()) {
            final String errorMessage = "Tried to access visual service "
                    + SystemServiceRegistry.getSystemServiceClassName(name)
                    + " from a non-visual Context. ";
            final String message = "Visual services, such as WindowManager, WallpaperService or "
                    + "LayoutInflater should be accessed from Activity or other visual Context. "
                    + "Use an Activity or a Context created with "
                    + "Context#createWindowContext(int, Bundle), which are adjusted to the "
                    + "configuration and visual bounds of an area on screen.";
            final Exception exception = new IllegalAccessException(errorMessage);
            StrictMode.onIncorrectContextUsed(message, exception);
            Log.e(TAG, errorMessage + message, exception);
        }

        return SystemServiceRegistry.getSystemService(this, name);
    }

    @Override
    public String getSystemServiceName(Class<?> serviceClass) {
        return SystemServiceRegistry.getSystemServiceName(serviceClass);
    }

    /** @hide */
    @Override
    public boolean isUiContext() {
        return mIsSystemOrSystemUiContext || mIsUiContext || isSystemOrSystemUI();
    }

    /**
     * Temporary workaround to permit incorrect usages of Context by SystemUI.
     * TODO(b/149790106): Fix usages and remove.
     */
    private boolean isSystemOrSystemUI() {
        return ActivityThread.isSystem() || checkPermission("android.permission.STATUS_BAR_SERVICE",
                Binder.getCallingPid(), Binder.getCallingUid()) == PERMISSION_GRANTED;
    }

    private static boolean isUiComponent(String name) {
        return WINDOW_SERVICE.equals(name) || LAYOUT_INFLATER_SERVICE.equals(name)
                || WALLPAPER_SERVICE.equals(name);
    }

    @Override
    public int checkPermission(String permission, int pid, int uid) {
        if (permission == null) {
            throw new IllegalArgumentException("permission is null");
        }
        return PermissionManager.checkPermission(permission, pid, uid);
    }

    /** @hide */
    @Override
    public int checkPermission(String permission, int pid, int uid, IBinder callerToken) {
        if (permission == null) {
            throw new IllegalArgumentException("permission is null");
        }

        try {
            return ActivityManager.getService().checkPermissionWithToken(
                    permission, pid, uid, callerToken);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
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
        if (resultOfCheck != PERMISSION_GRANTED) {
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
            ActivityManager.getService().grantUriPermission(
                    mMainThread.getApplicationThread(), toPackage,
                    ContentProvider.getUriWithoutUserId(uri), modeFlags, resolveUserId(uri));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void revokeUriPermission(Uri uri, int modeFlags) {
         try {
            ActivityManager.getService().revokeUriPermission(
                    mMainThread.getApplicationThread(), null,
                    ContentProvider.getUriWithoutUserId(uri), modeFlags, resolveUserId(uri));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void revokeUriPermission(String targetPackage, Uri uri, int modeFlags) {
        try {
            ActivityManager.getService().revokeUriPermission(
                    mMainThread.getApplicationThread(), targetPackage,
                    ContentProvider.getUriWithoutUserId(uri), modeFlags, resolveUserId(uri));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public int checkUriPermission(Uri uri, int pid, int uid, int modeFlags) {
        try {
            return ActivityManager.getService().checkUriPermission(
                    ContentProvider.getUriWithoutUserId(uri), pid, uid, modeFlags,
                    resolveUserId(uri), null);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    @Override
    public int checkUriPermission(Uri uri, int pid, int uid, int modeFlags, IBinder callerToken) {
        try {
            return ActivityManager.getService().checkUriPermission(
                    ContentProvider.getUriWithoutUserId(uri), pid, uid, modeFlags,
                    resolveUserId(uri), callerToken);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
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
                    == PERMISSION_GRANTED) {
                return PERMISSION_GRANTED;
            }
        }
        if ((modeFlags&Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) {
            if (writePermission == null
                    || checkPermission(writePermission, pid, uid)
                    == PERMISSION_GRANTED) {
                return PERMISSION_GRANTED;
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
        if (resultOfCheck != PERMISSION_GRANTED) {
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

    private static Resources createResources(IBinder activityToken, LoadedApk pi, String splitName,
            int displayId, Configuration overrideConfig, CompatibilityInfo compatInfo,
            List<ResourcesLoader> resourcesLoader) {
        final String[] splitResDirs;
        final ClassLoader classLoader;
        try {
            splitResDirs = pi.getSplitPaths(splitName);
            classLoader = pi.getSplitClassLoader(splitName);
        } catch (NameNotFoundException e) {
            throw new RuntimeException(e);
        }
        return ResourcesManager.getInstance().getResources(activityToken,
                pi.getResDir(),
                splitResDirs,
                pi.getOverlayDirs(),
                pi.getApplicationInfo().sharedLibraryFiles,
                displayId,
                overrideConfig,
                compatInfo,
                classLoader,
                resourcesLoader);
    }

    @Override
    public Context createApplicationContext(ApplicationInfo application, int flags)
            throws NameNotFoundException {
        LoadedApk pi = mMainThread.getPackageInfo(application, mResources.getCompatibilityInfo(),
                flags | CONTEXT_REGISTER_PACKAGE);
        if (pi != null) {
            ContextImpl c = new ContextImpl(this, mMainThread, pi, null, null, mToken,
                    new UserHandle(UserHandle.getUserId(application.uid)), flags, null, null);

            final int displayId = getDisplayId();

            c.setResources(createResources(mToken, pi, null, displayId, null,
                    getDisplayAdjustments(displayId).getCompatibilityInfo(), null));
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
        return createPackageContextAsUser(packageName, flags, mUser);
    }

    @Override
    public Context createPackageContextAsUser(String packageName, int flags, UserHandle user)
            throws NameNotFoundException {
        if (packageName.equals("system") || packageName.equals("android")) {
            // The system resources are loaded in every application, so we can safely copy
            // the context without reloading Resources.
            return new ContextImpl(this, mMainThread, mPackageInfo, mAttributionTag, null,
                    mToken, user, flags, null, null);
        }

        LoadedApk pi = mMainThread.getPackageInfo(packageName, mResources.getCompatibilityInfo(),
                flags | CONTEXT_REGISTER_PACKAGE, user.getIdentifier());
        if (pi != null) {
            ContextImpl c = new ContextImpl(this, mMainThread, pi, mAttributionTag, null,
                    mToken, user, flags, null, null);

            final int displayId = getDisplayId();

            c.setResources(createResources(mToken, pi, null, displayId, null,
                    getDisplayAdjustments(displayId).getCompatibilityInfo(), null));
            if (c.mResources != null) {
                return c;
            }
        }

        // Should be a better exception.
        throw new PackageManager.NameNotFoundException(
                "Application package " + packageName + " not found");
    }

    @Override
    public Context createContextAsUser(UserHandle user, @CreatePackageOptions int flags) {
        try {
            return createPackageContextAsUser(getPackageName(), flags, user);
        } catch (NameNotFoundException e) {
            throw new IllegalStateException("Own package not found: package=" + getPackageName());
        }
    }

    @Override
    public Context createContextForSplit(String splitName) throws NameNotFoundException {
        if (!mPackageInfo.getApplicationInfo().requestsIsolatedSplitLoading()) {
            // All Splits are always loaded.
            return this;
        }

        final ClassLoader classLoader = mPackageInfo.getSplitClassLoader(splitName);
        final String[] paths = mPackageInfo.getSplitPaths(splitName);

        final ContextImpl context = new ContextImpl(this, mMainThread, mPackageInfo,
                mAttributionTag, splitName, mToken, mUser, mFlags, classLoader, null);

        final int displayId = getDisplayId();

        context.setResources(ResourcesManager.getInstance().getResources(
                mToken,
                mPackageInfo.getResDir(),
                paths,
                mPackageInfo.getOverlayDirs(),
                mPackageInfo.getApplicationInfo().sharedLibraryFiles,
                displayId,
                null,
                mPackageInfo.getCompatibilityInfo(),
                classLoader,
                mResources.getLoaders()));
        return context;
    }

    @Override
    public Context createConfigurationContext(Configuration overrideConfiguration) {
        if (overrideConfiguration == null) {
            throw new IllegalArgumentException("overrideConfiguration must not be null");
        }

        ContextImpl context = new ContextImpl(this, mMainThread, mPackageInfo, mAttributionTag,
                mSplitName, mToken, mUser, mFlags, mClassLoader, null);

        final int displayId = getDisplayId();

        context.setResources(createResources(mToken, mPackageInfo, mSplitName, displayId,
                overrideConfiguration, getDisplayAdjustments(displayId).getCompatibilityInfo(),
                mResources.getLoaders()));
        return context;
    }

    @Override
    public Context createDisplayContext(Display display) {
        if (display == null) {
            throw new IllegalArgumentException("display must not be null");
        }

        ContextImpl context = new ContextImpl(this, mMainThread, mPackageInfo, mAttributionTag,
                mSplitName, mToken, mUser, mFlags, mClassLoader, null);

        final int displayId = display.getDisplayId();

        context.setResources(createResources(mToken, mPackageInfo, mSplitName, displayId,
                null, getDisplayAdjustments(displayId).getCompatibilityInfo(),
                mResources.getLoaders()));
        context.mDisplay = display;
        context.mIsAssociatedWithDisplay = true;
        return context;
    }

    @Override
    public @NonNull WindowContext createWindowContext(int type, Bundle options) {
        if (getDisplay() == null) {
            throw new UnsupportedOperationException("WindowContext can only be created from "
                    + "other visual contexts, such as Activity or one created with "
                    + "Context#createDisplayContext(Display)");
        }
        return new WindowContext(this, type, options);
    }

    ContextImpl createBaseWindowContext(IBinder token) {
        ContextImpl context = new ContextImpl(this, mMainThread, mPackageInfo, mAttributionTag,
                mSplitName, token, mUser, mFlags, mClassLoader, null);
        context.mIsUiContext = true;

        context.mIsAssociatedWithDisplay = true;
        return context;
    }

    Resources createWindowContextResources() {
        final String resDir = mPackageInfo.getResDir();
        final String[] splitResDirs = mPackageInfo.getSplitResDirs();
        final String[] overlayDirs = mPackageInfo.getOverlayDirs();
        final String[] libDirs = mPackageInfo.getApplicationInfo().sharedLibraryFiles;
        final int displayId = getDisplayId();
        final CompatibilityInfo compatInfo = (displayId == Display.DEFAULT_DISPLAY)
                ? mPackageInfo.getCompatibilityInfo()
                : CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO;
        final List<ResourcesLoader> loaders = mResources.getLoaders();

        return mResourcesManager.createBaseTokenResources(mToken, resDir, splitResDirs,
                overlayDirs, libDirs, displayId, null /* overrideConfig */,
                compatInfo, mClassLoader, loaders);
    }

    @Override
    public @NonNull Context createAttributionContext(@Nullable String attributionTag) {
        return new ContextImpl(this, mMainThread, mPackageInfo, attributionTag, mSplitName,
                mToken, mUser, mFlags, mClassLoader, null);
    }

    @Override
    public Context createDeviceProtectedStorageContext() {
        final int flags = (mFlags & ~Context.CONTEXT_CREDENTIAL_PROTECTED_STORAGE)
                | Context.CONTEXT_DEVICE_PROTECTED_STORAGE;
        return new ContextImpl(this, mMainThread, mPackageInfo, mAttributionTag, mSplitName,
                mToken, mUser, flags, mClassLoader, null);
    }

    @Override
    public Context createCredentialProtectedStorageContext() {
        final int flags = (mFlags & ~Context.CONTEXT_DEVICE_PROTECTED_STORAGE)
                | Context.CONTEXT_CREDENTIAL_PROTECTED_STORAGE;
        return new ContextImpl(this, mMainThread, mPackageInfo, mAttributionTag, mSplitName,
                mToken, mUser, flags, mClassLoader, null);
    }

    @Override
    public boolean isRestricted() {
        return (mFlags & Context.CONTEXT_RESTRICTED) != 0;
    }

    @Override
    public boolean isDeviceProtectedStorage() {
        return (mFlags & Context.CONTEXT_DEVICE_PROTECTED_STORAGE) != 0;
    }

    @Override
    public boolean isCredentialProtectedStorage() {
        return (mFlags & Context.CONTEXT_CREDENTIAL_PROTECTED_STORAGE) != 0;
    }

    @Override
    public boolean canLoadUnsafeResources() {
        if (getPackageName().equals(getOpPackageName())) {
            return true;
        }
        return (mFlags & Context.CONTEXT_IGNORE_SECURITY) != 0;
    }

    @Override
    public Display getDisplay() {
        if (!mIsSystemOrSystemUiContext && !mIsAssociatedWithDisplay && !isSystemOrSystemUI()) {
            throw new UnsupportedOperationException("Tried to obtain display from a Context not "
                    + "associated with  one. Only visual Contexts (such as Activity or one created "
                    + "with Context#createWindowContext) or ones created with "
                    + "Context#createDisplayContext are associated with displays. Other types of "
                    + "Contexts are typically related to background entities and may return an "
                    + "arbitrary display.");
        }
        return getDisplayNoVerify();
    }

    @Override
    public Display getDisplayNoVerify() {
        if (mDisplay == null) {
            return mResourcesManager.getAdjustedDisplay(Display.DEFAULT_DISPLAY,
                    mResources);
        }

        return mDisplay;
    }

    @Override
    public int getDisplayId() {
        final Display display = getDisplayNoVerify();
        return display != null ? display.getDisplayId() : Display.DEFAULT_DISPLAY;
    }

    @Override
    public void updateDisplay(int displayId) {
        mDisplay = mResourcesManager.getAdjustedDisplay(displayId, mResources);
        mIsAssociatedWithDisplay = true;
    }

    @Override
    public DisplayAdjustments getDisplayAdjustments(int displayId) {
        return mResources.getDisplayAdjustments();
    }

    @Override
    public File getDataDir() {
        if (mPackageInfo != null) {
            File res = null;
            if (isCredentialProtectedStorage()) {
                res = mPackageInfo.getCredentialProtectedDataDirFile();
            } else if (isDeviceProtectedStorage()) {
                res = mPackageInfo.getDeviceProtectedDataDirFile();
            } else {
                res = mPackageInfo.getDataDirFile();
            }

            if (res != null) {
                if (!res.exists() && android.os.Process.myUid() == android.os.Process.SYSTEM_UID) {
                    Log.wtf(TAG, "Data directory doesn't exist for package " + getPackageName(),
                            new Throwable());
                }
                return res;
            } else {
                throw new RuntimeException(
                        "No data directory found for package " + getPackageName());
            }
        } else {
            throw new RuntimeException(
                    "No package details found for package " + getPackageName());
        }
    }

    @Override
    public File getDir(String name, int mode) {
        checkMode(mode);
        name = "app_" + name;
        File file = makeFilename(getDataDir(), name);
        if (!file.exists()) {
            file.mkdir();
            setFilePermissionsFromMode(file.getPath(), mode,
                    FileUtils.S_IRWXU|FileUtils.S_IRWXG|FileUtils.S_IXOTH);
        }
        return file;
    }

    /** {@hide} */
    @Override
    public UserHandle getUser() {
        return mUser;
    }

    /** {@hide} */
    @Override
    public int getUserId() {
        return mUser.getIdentifier();
    }

    /** @hide */
    @Override
    public AutofillClient getAutofillClient() {
        return mAutofillClient;
    }

    /** @hide */
    @Override
    public void setAutofillClient(AutofillClient client) {
        mAutofillClient = client;
    }

    /** @hide */
    @Override
    public AutofillOptions getAutofillOptions() {
        return mAutofillOptions;
    }

    /** @hide */
    @Override
    public void setAutofillOptions(AutofillOptions options) {
        mAutofillOptions = options;
    }

    /** @hide */
    @Override
    public ContentCaptureOptions getContentCaptureOptions() {
        return mContentCaptureOptions;
    }

    /** @hide */
    @Override
    public void setContentCaptureOptions(ContentCaptureOptions options) {
        mContentCaptureOptions = options;
    }

    @UnsupportedAppUsage
    static ContextImpl createSystemContext(ActivityThread mainThread) {
        LoadedApk packageInfo = new LoadedApk(mainThread);
        ContextImpl context = new ContextImpl(null, mainThread, packageInfo, null, null, null, null,
                0, null, null);
        context.setResources(packageInfo.getResources());
        context.mResources.updateConfiguration(context.mResourcesManager.getConfiguration(),
                context.mResourcesManager.getDisplayMetrics());
        context.mIsSystemOrSystemUiContext = true;
        return context;
    }

    /**
     * System Context to be used for UI. This Context has resources that can be themed.
     * Make sure that the created system UI context shares the same LoadedApk as the system context.
     * @param systemContext The system context which created by
     *                      {@link #createSystemContext(ActivityThread)}.
     * @param displayId The ID of the display where the UI is shown.
     */
    static ContextImpl createSystemUiContext(ContextImpl systemContext, int displayId) {
        final LoadedApk packageInfo = systemContext.mPackageInfo;
        ContextImpl context = new ContextImpl(null, systemContext.mMainThread, packageInfo, null,
                null, null, null, 0, null, null);
        context.setResources(createResources(null, packageInfo, null, displayId, null,
                packageInfo.getCompatibilityInfo(), null));
        context.updateDisplay(displayId);
        context.mIsSystemOrSystemUiContext = true;
        return context;
    }

    /**
     * The overloaded method of {@link #createSystemUiContext(ContextImpl, int)}.
     * Uses {@Code Display.DEFAULT_DISPLAY} as the target display.
     */
    static ContextImpl createSystemUiContext(ContextImpl systemContext) {
        return createSystemUiContext(systemContext, Display.DEFAULT_DISPLAY);
    }

    @UnsupportedAppUsage
    static ContextImpl createAppContext(ActivityThread mainThread, LoadedApk packageInfo) {
        return createAppContext(mainThread, packageInfo, null);
    }

    static ContextImpl createAppContext(ActivityThread mainThread, LoadedApk packageInfo,
            String opPackageName) {
        if (packageInfo == null) throw new IllegalArgumentException("packageInfo");
        ContextImpl context = new ContextImpl(null, mainThread, packageInfo, null, null, null, null,
                0, null, opPackageName);
        context.setResources(packageInfo.getResources());
        return context;
    }

    @UnsupportedAppUsage
    static ContextImpl createActivityContext(ActivityThread mainThread,
            LoadedApk packageInfo, ActivityInfo activityInfo, IBinder activityToken, int displayId,
            Configuration overrideConfiguration) {
        if (packageInfo == null) throw new IllegalArgumentException("packageInfo");

        String[] splitDirs = packageInfo.getSplitResDirs();
        ClassLoader classLoader = packageInfo.getClassLoader();

        if (packageInfo.getApplicationInfo().requestsIsolatedSplitLoading()) {
            Trace.traceBegin(Trace.TRACE_TAG_RESOURCES, "SplitDependencies");
            try {
                classLoader = packageInfo.getSplitClassLoader(activityInfo.splitName);
                splitDirs = packageInfo.getSplitPaths(activityInfo.splitName);
            } catch (NameNotFoundException e) {
                // Nothing above us can handle a NameNotFoundException, better crash.
                throw new RuntimeException(e);
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_RESOURCES);
            }
        }

        ContextImpl context = new ContextImpl(null, mainThread, packageInfo, null,
                activityInfo.splitName, activityToken, null, 0, classLoader, null);
        context.mIsUiContext = true;
        context.mIsAssociatedWithDisplay = true;

        // Clamp display ID to DEFAULT_DISPLAY if it is INVALID_DISPLAY.
        displayId = (displayId != Display.INVALID_DISPLAY) ? displayId : Display.DEFAULT_DISPLAY;

        final CompatibilityInfo compatInfo = (displayId == Display.DEFAULT_DISPLAY)
                ? packageInfo.getCompatibilityInfo()
                : CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO;

        final ResourcesManager resourcesManager = ResourcesManager.getInstance();

        // Create the base resources for which all configuration contexts for this Activity
        // will be rebased upon.
        context.setResources(resourcesManager.createBaseTokenResources(activityToken,
                packageInfo.getResDir(),
                splitDirs,
                packageInfo.getOverlayDirs(),
                packageInfo.getApplicationInfo().sharedLibraryFiles,
                displayId,
                overrideConfiguration,
                compatInfo,
                classLoader,
                packageInfo.getApplication() == null ? null
                        : packageInfo.getApplication().getResources().getLoaders()));
        context.mDisplay = resourcesManager.getAdjustedDisplay(displayId,
                context.getResources());
        return context;
    }

    private ContextImpl(@Nullable ContextImpl container, @NonNull ActivityThread mainThread,
            @NonNull LoadedApk packageInfo, @Nullable String attributionTag,
            @Nullable String splitName, @Nullable IBinder activityToken, @Nullable UserHandle user,
            int flags, @Nullable ClassLoader classLoader, @Nullable String overrideOpPackageName) {
        mOuterContext = this;

        // If creator didn't specify which storage to use, use the default
        // location for application.
        if ((flags & (Context.CONTEXT_CREDENTIAL_PROTECTED_STORAGE
                | Context.CONTEXT_DEVICE_PROTECTED_STORAGE)) == 0) {
            final File dataDir = packageInfo.getDataDirFile();
            if (Objects.equals(dataDir, packageInfo.getCredentialProtectedDataDirFile())) {
                flags |= Context.CONTEXT_CREDENTIAL_PROTECTED_STORAGE;
            } else if (Objects.equals(dataDir, packageInfo.getDeviceProtectedDataDirFile())) {
                flags |= Context.CONTEXT_DEVICE_PROTECTED_STORAGE;
            }
        }

        mMainThread = mainThread;
        mToken = activityToken;
        mFlags = flags;

        if (user == null) {
            user = Process.myUserHandle();
        }
        mUser = user;

        mPackageInfo = packageInfo;
        mSplitName = splitName;
        mClassLoader = classLoader;
        mResourcesManager = ResourcesManager.getInstance();

        String opPackageName;

        if (container != null) {
            mBasePackageName = container.mBasePackageName;
            opPackageName = container.mOpPackageName;
            setResources(container.mResources);
            mDisplay = container.mDisplay;
            mIsSystemOrSystemUiContext = container.mIsSystemOrSystemUiContext;
        } else {
            mBasePackageName = packageInfo.mPackageName;
            ApplicationInfo ainfo = packageInfo.getApplicationInfo();
            if (ainfo.uid == Process.SYSTEM_UID && ainfo.uid != Process.myUid()) {
                // Special case: system components allow themselves to be loaded in to other
                // processes.  For purposes of app ops, we must then consider the context as
                // belonging to the package of this process, not the system itself, otherwise
                // the package+uid verifications in app ops will fail.
                opPackageName = ActivityThread.currentPackageName();
            } else {
                opPackageName = mBasePackageName;
            }
        }

        mOpPackageName = overrideOpPackageName != null ? overrideOpPackageName : opPackageName;
        mAttributionTag = attributionTag;
        mContentResolver = new ApplicationContentResolver(this, mainThread);
    }

    void setResources(Resources r) {
        if (r instanceof CompatResources) {
            ((CompatResources) r).setContext(this);
        }
        mResources = r;
    }

    void installSystemApplicationInfo(ApplicationInfo info, ClassLoader classLoader) {
        mPackageInfo.installSystemApplicationInfo(info, classLoader);
    }

    @UnsupportedAppUsage
    final void scheduleFinalCleanup(String who, String what) {
        mMainThread.scheduleContextCleanup(this, who, what);
    }

    final void performFinalCleanup(String who, String what) {
        //Log.i(TAG, "Cleanup up context: " + this);
        mPackageInfo.removeContextRegistrations(getOuterContext(), who, what);
    }

    @UnsupportedAppUsage
    final Context getReceiverRestrictedContext() {
        if (mReceiverRestrictedContext != null) {
            return mReceiverRestrictedContext;
        }
        return mReceiverRestrictedContext = new ReceiverRestrictedContext(getOuterContext());
    }

    @UnsupportedAppUsage
    final void setOuterContext(Context context) {
        mOuterContext = context;
    }

    @UnsupportedAppUsage
    final Context getOuterContext() {
        return mOuterContext;
    }

    @Override
    @UnsupportedAppUsage
    public IBinder getActivityToken() {
        return mToken;
    }

    private void checkMode(int mode) {
        if (getApplicationInfo().targetSdkVersion >= Build.VERSION_CODES.N) {
            if ((mode & MODE_WORLD_READABLE) != 0) {
                throw new SecurityException("MODE_WORLD_READABLE no longer supported");
            }
            if ((mode & MODE_WORLD_WRITEABLE) != 0) {
                throw new SecurityException("MODE_WORLD_WRITEABLE no longer supported");
            }
        }
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

    private File makeFilename(File base, String name) {
        if (name.indexOf(File.separatorChar) < 0) {
            final File res = new File(base, name);
            // We report as filesystem access here to give us the best shot at
            // detecting apps that will pass the path down to native code.
            BlockGuard.getVmPolicy().onPathAccess(res.getPath());
            return res;
        }
        throw new IllegalArgumentException(
                "File " + name + " contains a path separator");
    }

    /**
     * Ensure that given directories exist, trying to create them if missing. If
     * unable to create, they are filtered by replacing with {@code null}.
     */
    private File[] ensureExternalDirsExistOrFilter(File[] dirs, boolean tryCreateInProcess) {
        final StorageManager sm = getSystemService(StorageManager.class);
        final File[] result = new File[dirs.length];
        for (int i = 0; i < dirs.length; i++) {
            File dir = dirs[i];
            if (!dir.exists()) {
                try {
                    if (!tryCreateInProcess || !dir.mkdirs()) {
                        // recheck existence in case of cross-process race
                        if (!dir.exists()) {
                            // Failing to mkdir() may be okay, since we might not have
                            // enough permissions; ask vold to create on our behalf.
                            sm.mkdirs(dir);
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to ensure " + dir + ": " + e);
                    dir = null;
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
        @UnsupportedAppUsage
        private final ActivityThread mMainThread;

        public ApplicationContentResolver(Context context, ActivityThread mainThread) {
            super(context);
            mMainThread = Objects.requireNonNull(mainThread);
        }

        @Override
        @UnsupportedAppUsage
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
            return ContentProvider.getUserIdFromAuthority(auth, getUserId());
        }
    }
}
