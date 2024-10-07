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

import static android.content.pm.PackageManager.PERMISSION_DENIED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.StrictMode.vmIncorrectContextUseEnabled;
import static android.permission.flags.Flags.shouldRegisterAttributionSource;
import static android.view.WindowManager.LayoutParams.WindowType;

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.UiContext;
import android.companion.virtual.VirtualDevice;
import android.companion.virtual.VirtualDeviceManager;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.AttributionSource;
import android.content.AutofillOptions;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentCaptureOptions;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextParams;
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
import android.permission.PermissionControllerManager;
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
import android.window.WindowContext;
import android.window.WindowTokenClient;
import android.window.WindowTokenClientController;

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
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.IntConsumer;

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

    /**
     * The {@link com.android.server.wm.WindowToken} representing this instance if it is
     * {@link #CONTEXT_TYPE_WINDOW_CONTEXT} or {@link #CONTEXT_TYPE_SYSTEM_OR_SYSTEM_UI}.
     * If the type is {@link #CONTEXT_TYPE_ACTIVITY}, then represents the
     * {@link android.window.WindowContainerToken} of the activity.
     */
    private final @Nullable IBinder mToken;

    private final @NonNull UserHandle mUser;

    @UnsupportedAppUsage
    private final ApplicationContentResolver mContentResolver;

    @UnsupportedAppUsage
    private final String mBasePackageName;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private final String mOpPackageName;
    private final @NonNull ContextParams mParams;
    private @NonNull AttributionSource mAttributionSource;

    private final @NonNull ResourcesManager mResourcesManager;
    @UnsupportedAppUsage
    private @NonNull Resources mResources;
    private @Nullable Display mDisplay; // may be null if invalid display or not initialized yet.
    private int mDeviceId = Context.DEVICE_ID_DEFAULT;

    /**
     * If set to {@code true} the resources for this context will be configured for mDisplay which
     * will override the display configuration inherited from {@link #mToken} (or the global
     * configuration if mToken is null). Typically set for display contexts and contexts derived
     * from display contexts where changes to the activity display and the global configuration
     * display should not impact their resources.
     */
    private boolean mForceDisplayOverrideInResources;

    /** @see Context#isConfigurationContext() */
    private boolean mIsConfigurationBasedContext;

    /**
     *  Indicates that this context was created with an explicit device ID association via
     *  Context#createDeviceContext and under no circumstances will it ever change, even if
     *  this context is not associated with a display id, or if the associated display id changes.
     */
    private boolean mIsExplicitDeviceId = false;

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private final int mFlags;

    @UnsupportedAppUsage
    private Context mOuterContext;

    private final Object mThemeLock = new Object();
    @UnsupportedAppUsage
    @GuardedBy("mThemeLock")
    private int mThemeResource = 0;
    @UnsupportedAppUsage
    @GuardedBy("mThemeLock")
    private Resources.Theme mTheme = null;

    @UnsupportedAppUsage
    private PackageManager mPackageManager;
    private Context mReceiverRestrictedContext = null;

    // The name of the split this Context is representing. May be null.
    private @Nullable String mSplitName = null;

    private @Nullable AutofillClient mAutofillClient = null;
    private @Nullable AutofillOptions mAutofillOptions;

    private ContentCaptureOptions mContentCaptureOptions = null;

    /**
     * Indicates this {@link Context} can not handle UI components properly and is not associated
     * with a {@link Display} instance.
     */
    private static final int CONTEXT_TYPE_NON_UI = 0;
    /**
     * Indicates this {@link Context} is associated with a {@link Display} instance but should not
     * be handled UI components properly because it doesn't receive configuration changes
     * regardless of display property updates.
     */
    private static final int CONTEXT_TYPE_DISPLAY_CONTEXT = 1;
    /**
     * Indicates this {@link Context} is an {@link Activity} or {@link Activity} derived
     * {@link Context}.
     */
    private static final int CONTEXT_TYPE_ACTIVITY = 2;
    /**
     * Indicates this {@link Context} is a {@link WindowContext} or {@link WindowContext} derived
     * {@link Context}.
     */
    private static final int CONTEXT_TYPE_WINDOW_CONTEXT = 3;

    // TODO(b/170369943): Remove after WindowContext migration
    /**
     * Indicates this {@link Context} is created from {@link #createSystemContext(ActivityThread)}
     * or {@link #createSystemUiContext(ContextImpl, int)} or any {@link Context} that system UI
     * uses.
     */
    private static final int CONTEXT_TYPE_SYSTEM_OR_SYSTEM_UI = 4;

    @IntDef(prefix = "CONTEXT_TYPE_", value = {
            CONTEXT_TYPE_NON_UI,
            CONTEXT_TYPE_DISPLAY_CONTEXT,
            CONTEXT_TYPE_ACTIVITY,
            CONTEXT_TYPE_WINDOW_CONTEXT,
            CONTEXT_TYPE_SYSTEM_OR_SYSTEM_UI
    })
    @Retention(RetentionPolicy.SOURCE)
    private @interface ContextType {}

    @ContextType
    private int mContextType;

    /**
     * {@code true} to indicate that the {@link Context} owns the {@link #getWindowContextToken()}
     * and is responsible for detaching the token when the Context is released.
     *
     * @see #finalize()
     */
    private boolean mOwnsToken = false;

    private final Object mDatabasesDirLock = new Object();
    @GuardedBy("mDatabasesDirLock")
    private File mDatabasesDir;

    private final Object mPreferencesDirLock = new Object();
    @UnsupportedAppUsage
    @GuardedBy("mPreferencesDirLock")
    private File mPreferencesDir;

    private final Object mFilesDirLock = new Object();
    @GuardedBy("mFilesDirLock")
    private File mFilesDir;

    private final Object mCratesDirLock = new Object();
    @GuardedBy("mCratesDirLock")
    private File mCratesDir;

    private final Object mNoBackupFilesDirLock = new Object();
    @GuardedBy("mNoBackupFilesDirLock")
    private File mNoBackupFilesDir;

    private final Object mCacheDirLock = new Object();
    @GuardedBy("mCacheDirLock")
    private File mCacheDir;

    private final Object mCodeCacheDirLock = new Object();
    @GuardedBy("mCodeCacheDirLock")
    private File mCodeCacheDir;

    private final Object mMiscDirsLock = new Object();

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

    private final Object mDeviceIdListenerLock = new Object();
    /**
     * List of listeners for deviceId changes and their associated Executor.
     * List is lazy-initialized on first registration
     */
    @GuardedBy("mDeviceIdListenerLock")
    @Nullable
    private ArrayList<DeviceIdChangeListenerDelegate> mDeviceIdChangeListeners;

    private static class DeviceIdChangeListenerDelegate {
        final @NonNull IntConsumer mListener;
        final @NonNull Executor mExecutor;
        DeviceIdChangeListenerDelegate(IntConsumer listener, Executor executor) {
            mListener = listener;
            mExecutor = executor;
        }
    }

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
        synchronized (mThemeLock) {
            if (mThemeResource != resId) {
                mThemeResource = resId;
                initializeTheme();
            }
        }
    }

    @Override
    public int getThemeResId() {
        synchronized (mThemeLock) {
            return mThemeResource;
        }
    }

    @Override
    public Resources.Theme getTheme() {
        synchronized (mThemeLock) {
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
        return mAttributionSource.getPackageName();
    }

    /** @hide */
    @Override
    public @Nullable String getAttributionTag() {
        return mAttributionSource.getAttributionTag();
    }

    @Override
    public @Nullable ContextParams getParams() {
        return mParams;
    }

    @Override
    public @NonNull AttributionSource getAttributionSource() {
        return mAttributionSource;
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
                    if (isCredentialProtectedStorage()) {
                        final UserManager um = getSystemService(UserManager.class);
                        if (um == null) {
                            throw new IllegalStateException("SharedPreferences cannot be accessed "
                                    + "if UserManager is not available. "
                                    + "(e.g. from inside an isolated process)");
                        }
                        if (!um.isUserUnlockingOrUnlocked(UserHandle.myUserId())) {
                            throw new IllegalStateException("SharedPreferences in "
                                    + "credential encrypted storage are not available until after "
                                    + "user (id " + UserHandle.myUserId() + ") is unlocked");
                        }
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
        synchronized (mPreferencesDirLock) {
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
        synchronized (mFilesDirLock) {
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

        synchronized (mCratesDirLock) {
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
        synchronized (mNoBackupFilesDirLock) {
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
        synchronized (mMiscDirsLock) {
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
        synchronized (mMiscDirsLock) {
            File[] dirs = Environment.buildExternalStorageAppObbDirs(getPackageName());
            return ensureExternalDirsExistOrFilter(dirs, true /* tryCreateInProcess */);
        }
    }

    @Override
    public File getCacheDir() {
        synchronized (mCacheDirLock) {
            if (mCacheDir == null) {
                mCacheDir = new File(getDataDir(), "cache");
            }
            return ensurePrivateCacheDirExists(mCacheDir, XATTR_INODE_CACHE);
        }
    }

    @Override
    public File getCodeCacheDir() {
        synchronized (mCodeCacheDirLock) {
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
        synchronized (mMiscDirsLock) {
            File[] dirs = Environment.buildExternalStorageAppCacheDirs(getPackageName());
            // We don't try to create cache directories in-process, because they need special
            // setup for accurate quota tracking. This ensures the cache dirs are always
            // created through StorageManagerService.
            return ensureExternalDirsExistOrFilter(dirs, false /* tryCreateInProcess */);
        }
    }

    @Override
    public File[] getExternalMediaDirs() {
        synchronized (mMiscDirsLock) {
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
        synchronized (mDatabasesDirLock) {
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
                    "Calling startActivity() from outside of an Activity"
                            + " context requires the FLAG_ACTIVITY_NEW_TASK flag."
                            + " Is this really what you want?");
        }
        mMainThread.getInstrumentation().execStartActivity(
                getOuterContext(), mMainThread.getApplicationThread(), null,
                (Activity) null, intent, -1, applyLaunchDisplayIfNeeded(options));
    }

    /** @hide */
    @Override
    public void startActivityAsUser(Intent intent, Bundle options, UserHandle user) {
        try {
            ActivityTaskManager.getService().startActivityAsUser(
                    mMainThread.getApplicationThread(), getOpPackageName(), getAttributionTag(),
                    intent, intent.resolveTypeIfNeeded(getContentResolver()),
                    null, null, 0, Intent.FLAG_ACTIVITY_NEW_TASK, null,
                    applyLaunchDisplayIfNeeded(options), user.getIdentifier());
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
                    "Calling startActivities() from outside of an Activity"
                    + " context requires the FLAG_ACTIVITY_NEW_TASK flag on first Intent."
                    + " Is this really what you want?");
        }
        return mMainThread.getInstrumentation().execStartActivitiesAsUser(
                getOuterContext(), mMainThread.getApplicationThread(), null,
                (Activity) null, intents, applyLaunchDisplayIfNeeded(options),
                userHandle.getIdentifier());
    }

    @Override
    public void startActivities(Intent[] intents, Bundle options) {
        warnIfCallingFromSystemProcess();
        if ((intents[0].getFlags()&Intent.FLAG_ACTIVITY_NEW_TASK) == 0) {
            throw new AndroidRuntimeException(
                    "Calling startActivities() from outside of an Activity"
                    + " context requires the FLAG_ACTIVITY_NEW_TASK flag on first Intent."
                    + " Is this really what you want?");
        }
        mMainThread.getInstrumentation().execStartActivities(
                getOuterContext(), mMainThread.getApplicationThread(), null,
                (Activity) null, intents, applyLaunchDisplayIfNeeded(options));
    }

    private Bundle applyLaunchDisplayIfNeeded(@Nullable Bundle options) {
        if (!isAssociatedWithDisplay()) {
            // return if this Context has no associated display.
            return options;
        }

        final ActivityOptions activityOptions;
        if (options != null) {
            activityOptions = ActivityOptions.fromBundle(options);
            if (ActivityOptions.hasLaunchTargetContainer(activityOptions)) {
                // return if the options already has launching target.
                return options;
            }
        } else {
            activityOptions = ActivityOptions.makeBasic();
        }
        return activityOptions.setLaunchDisplayId(getAssociatedDisplayId()).toBundle();
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
                fillInIntent.migrateExtraStreamToClipData(this);
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
                    null, Activity.RESULT_OK, null, null, null, null /*excludedPermissions=*/,
                    null, AppOpsManager.OP_NONE, null, false, false, getUserId());
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
                    null /*excludedPermissions=*/, null, AppOpsManager.OP_NONE, null, false, false,
                    getUserId());
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
                    null /*excludedPermissions=*/, null, AppOpsManager.OP_NONE, null, false, false,
                    getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void sendBroadcastMultiplePermissions(Intent intent, String[] receiverPermissions,
            Bundle options) {
        warnIfCallingFromSystemProcess();
        String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
        try {
            intent.prepareToLeaveProcess(this);
            ActivityManager.getService().broadcastIntentWithFeature(
                    mMainThread.getApplicationThread(), getAttributionTag(), intent, resolvedType,
                    null, Activity.RESULT_OK, null, null, receiverPermissions,
                    null /*excludedPermissions=*/, null /*excludedPackages*/,
                    AppOpsManager.OP_NONE, options, false, false, getUserId());
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
                    null /*excludedPermissions=*/, null, AppOpsManager.OP_NONE, null, false, false,
                    user.getIdentifier());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void sendBroadcastMultiplePermissions(Intent intent, String[] receiverPermissions,
            String[] excludedPermissions, String[] excludedPackages, BroadcastOptions options) {
        warnIfCallingFromSystemProcess();
        String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
        try {
            intent.prepareToLeaveProcess(this);
            ActivityManager.getService().broadcastIntentWithFeature(
                    mMainThread.getApplicationThread(), getAttributionTag(), intent, resolvedType,
                    null, Activity.RESULT_OK, null, null, receiverPermissions, excludedPermissions,
                    excludedPackages, AppOpsManager.OP_NONE,
                    options == null ? null : options.toBundle(), false, false, getUserId());
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
        String[] excludedPermissions = null;
        if (options != null) {
            String[] receiverPermissionsBundle = options.getStringArray(
                    BroadcastOptions.KEY_REQUIRE_ALL_OF_PERMISSIONS);
            if (receiverPermissionsBundle != null) {
                receiverPermissions = receiverPermissionsBundle;
            }
            excludedPermissions = options.getStringArray(
                    BroadcastOptions.KEY_REQUIRE_NONE_OF_PERMISSIONS);
        }
        try {
            intent.prepareToLeaveProcess(this);
            ActivityManager.getService().broadcastIntentWithFeature(
                    mMainThread.getApplicationThread(), getAttributionTag(), intent, resolvedType,
                    null, Activity.RESULT_OK, null, null, receiverPermissions,
                    excludedPermissions, null, AppOpsManager.OP_NONE, options, false, false,
                    getUserId());
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
                    null, Activity.RESULT_OK, null, null, receiverPermissions,
                    null /*excludedPermissions=*/, null, appOp, null, false, false, getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    @SuppressLint("AndroidFrameworkRequiresPermission")
    public void sendOrderedBroadcast(Intent intent, String receiverPermission) {
        sendOrderedBroadcast(intent, receiverPermission, /*options=*/ null);
    }

    @Override
    public void sendOrderedBroadcast(Intent intent, String receiverPermission, Bundle options) {
        warnIfCallingFromSystemProcess();
        String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
        String[] receiverPermissions = receiverPermission == null ? null
                : new String[] {receiverPermission};
        try {
            intent.prepareToLeaveProcess(this);
            ActivityManager.getService().broadcastIntentWithFeature(
                    mMainThread.getApplicationThread(), getAttributionTag(), intent, resolvedType,
                    null, Activity.RESULT_OK, null, null, receiverPermissions,
                    null /*excludedPermissions=*/, null, AppOpsManager.OP_NONE, options, true,
                    false, getUserId());
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
                rd = new LoadedApk.ReceiverDispatcher(mMainThread.getApplicationThread(),
                        resultReceiver, getOuterContext(), scheduler, null, false)
                                .getIIntentReceiver();
            }
        }
        String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
        String[] receiverPermissions = receiverPermission == null ? null
                : new String[] {receiverPermission};
        try {
            intent.prepareToLeaveProcess(this);
            ActivityManager.getService().broadcastIntentWithFeature(
                    mMainThread.getApplicationThread(), getAttributionTag(), intent, resolvedType,
                    rd, initialCode, initialData, initialExtras, receiverPermissions,
                    null /*excludedPermissions=*/, null, appOp, options, true, false, getUserId());
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
                    null, Activity.RESULT_OK, null, null, null, null /*excludedPermissions=*/,
                    null, AppOpsManager.OP_NONE, null, false, false, user.getIdentifier());
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
                    null /*excludedPermissions=*/, null, AppOpsManager.OP_NONE, options, false,
                    false, user.getIdentifier());
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
                    null, Activity.RESULT_OK, null, null, receiverPermissions,
                    null /*excludedPermissions=*/, null, appOp, null, false, false,
                    user.getIdentifier());
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
        String[] receiverPermissions = receiverPermission == null ? null
                : new String[] {receiverPermission};
        sendOrderedBroadcastAsUserMultiplePermissions(intent, user, receiverPermissions, appOp,
                options, resultReceiver, scheduler, initialCode, initialData, initialExtras);
    }

    @Override
    public void sendOrderedBroadcastAsUserMultiplePermissions(Intent intent, UserHandle user,
            String[] receiverPermissions, int appOp, Bundle options,
            BroadcastReceiver resultReceiver, Handler scheduler, int initialCode,
            String initialData, Bundle initialExtras) {
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
                rd = new LoadedApk.ReceiverDispatcher(mMainThread.getApplicationThread(),
                        resultReceiver, getOuterContext(), scheduler, null, false)
                                .getIIntentReceiver();
            }
        }
        String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
        try {
            intent.prepareToLeaveProcess(this);
            ActivityManager.getService().broadcastIntentWithFeature(
                    mMainThread.getApplicationThread(), getAttributionTag(), intent, resolvedType,
                    rd, initialCode, initialData, initialExtras, receiverPermissions,
                    null /*excludedPermissions=*/, null, appOp, options, true, false,
                    user.getIdentifier());
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
    public void sendOrderedBroadcastMultiplePermissions(Intent intent, String[] receiverPermissions,
            String receiverAppOp, BroadcastReceiver resultReceiver, Handler scheduler,
            int initialCode, String initialData, @Nullable Bundle initialExtras,
            @Nullable Bundle options) {
        int intAppOp = AppOpsManager.OP_NONE;
        if (!TextUtils.isEmpty(receiverAppOp)) {
            intAppOp = AppOpsManager.strOpToOp(receiverAppOp);
        }
        sendOrderedBroadcastAsUserMultiplePermissions(intent, getUser(), receiverPermissions,
                intAppOp, options, resultReceiver, scheduler, initialCode, initialData,
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
                    null, Activity.RESULT_OK, null, null, null, null /*excludedPermissions=*/,
                    null, AppOpsManager.OP_NONE, null, false, true, getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * <p>Perform a {@link #sendBroadcast(Intent)} that is "sticky," meaning the
     * Intent you are sending stays around after the broadcast is complete,
     * so that others can quickly retrieve that data through the return
     * value of {@link #registerReceiver(BroadcastReceiver, IntentFilter)}.  In
     * all other ways, this behaves the same as
     * {@link #sendBroadcast(Intent)}.
     *
     * @deprecated Sticky broadcasts should not be used.  They provide no security (anyone
     * can access them), no protection (anyone can modify them), and many other problems.
     * The recommended pattern is to use a non-sticky broadcast to report that <em>something</em>
     * has changed, with another mechanism for apps to retrieve the current value whenever
     * desired.
     *
     * @param intent The Intent to broadcast; all receivers matching this
     * Intent will receive the broadcast, and the Intent will be held to
     * be re-broadcast to future receivers.
     * @param options (optional) Additional sending options, generated from a
     * {@link android.app.BroadcastOptions}.
     *
     * @see #sendBroadcast(Intent)
     * @see #sendStickyOrderedBroadcast(Intent, BroadcastReceiver, Handler, int, String, Bundle)
     */
    @Override
    @Deprecated
    public void sendStickyBroadcast(@NonNull Intent intent, @Nullable Bundle options) {
        warnIfCallingFromSystemProcess();
        String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
        try {
            intent.prepareToLeaveProcess(this);
            ActivityManager.getService().broadcastIntentWithFeature(
                    mMainThread.getApplicationThread(), getAttributionTag(), intent, resolvedType,
                    null, Activity.RESULT_OK, null, null, null, null /*excludedPermissions=*/,
                    null, AppOpsManager.OP_NONE, options, false, true, getUserId());
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
                rd = new LoadedApk.ReceiverDispatcher(mMainThread.getApplicationThread(),
                        resultReceiver, getOuterContext(), scheduler, null, false)
                                .getIIntentReceiver();
            }
        }
        String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
        try {
            intent.prepareToLeaveProcess(this);
            ActivityManager.getService().broadcastIntentWithFeature(
                    mMainThread.getApplicationThread(), getAttributionTag(), intent, resolvedType,
                    rd, initialCode, initialData, initialExtras, null,
                    null /*excludedPermissions=*/, null, AppOpsManager.OP_NONE, null, true, true,
                    getUserId());
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
                    null, Activity.RESULT_OK, null, null, null, null /*excludedPermissions=*/,
                    null, AppOpsManager.OP_NONE, null, false, true, user.getIdentifier());
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
                    null, Activity.RESULT_OK, null, null, null, null /*excludedPermissions=*/,
                    null, AppOpsManager.OP_NONE, options, false, true, user.getIdentifier());
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
                rd = new LoadedApk.ReceiverDispatcher(mMainThread.getApplicationThread(),
                        resultReceiver, getOuterContext(), scheduler, null, false)
                                .getIIntentReceiver();
            }
        }
        String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
        try {
            intent.prepareToLeaveProcess(this);
            ActivityManager.getService().broadcastIntentWithFeature(
                    mMainThread.getApplicationThread(), getAttributionTag(), intent, resolvedType,
                    rd, initialCode, initialData, initialExtras, null,
                    null /*excludedPermissions=*/, null, AppOpsManager.OP_NONE, null, true, true,
                    user.getIdentifier());
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
    public Intent registerReceiverForAllUsers(BroadcastReceiver receiver,
            IntentFilter filter, String broadcastPermission, Handler scheduler, int flags) {
        return registerReceiverAsUser(receiver, UserHandle.ALL,
                filter, broadcastPermission, scheduler, flags);
    }

    @Override
    public Intent registerReceiverAsUser(BroadcastReceiver receiver, UserHandle user,
            IntentFilter filter, String broadcastPermission, Handler scheduler) {
        return registerReceiverInternal(receiver, user.getIdentifier(),
                filter, broadcastPermission, scheduler, getOuterContext(), 0);
    }

    @Override
    public Intent registerReceiverAsUser(BroadcastReceiver receiver, UserHandle user,
            IntentFilter filter, String broadcastPermission, Handler scheduler, int flags) {
        return registerReceiverInternal(receiver, user.getIdentifier(),
                filter, broadcastPermission, scheduler, getOuterContext(), flags);
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
                rd = new LoadedApk.ReceiverDispatcher(mMainThread.getApplicationThread(),
                        receiver, context, scheduler, null, true).getIIntentReceiver();
            }
        }
        try {
            final Intent intent;
            if (receiver == null && BroadcastStickyCache.useCache(filter)) {
                intent = BroadcastStickyCache.getIntentUnchecked(filter);
            } else {
                intent = ActivityManager.getService().registerReceiverWithFeature(
                        mMainThread.getApplicationThread(), mBasePackageName, getAttributionTag(),
                        AppOpsManager.toReceiverId(receiver), rd, filter, broadcastPermission,
                        userId,
                        flags);
                if (receiver == null) {
                    BroadcastStickyCache.add(filter, intent);
                }
            }
            if (intent != null) {
                intent.setExtrasClassLoader(getClassLoader());
                // TODO: determine at registration time if caller is
                // protecting themselves with signature permission
                intent.prepareToEnterProcess(ActivityThread.isProtectedBroadcast(intent),
                        getAttributionSource());
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
        // Keep this in sync with ActivityManagerLocal.startSdkSandboxService
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
                    throw ServiceStartNotAllowedException.newInstance(requireForeground,
                            "Not allowed to start service " + service + ": " + cn.getClassName());
                }
            }
            // If we started a foreground service in the same package, remember the stack trace.
            if (cn != null && requireForeground) {
                if (cn.getPackageName().equals(getOpPackageName())) {
                    Service.setStartForegroundServiceStackTrace(cn.getClassName(),
                            new StackTrace("Last startServiceCommon() call for this service was "
                                    + "made here"));
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
        // // Keep this in sync with ActivityManagerLocal.stopSdkSandboxService
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
        return bindServiceCommon(service, conn, Integer.toUnsignedLong(flags), null,
                mMainThread.getHandler(), null, getUser());
    }

    @Override
    public boolean bindService(Intent service, ServiceConnection conn,
            @NonNull BindServiceFlags flags) {
        warnIfCallingFromSystemProcess();
        return bindServiceCommon(service, conn, flags.getValue(), null, mMainThread.getHandler(),
                null, getUser());
    }

    @Override
    public boolean bindService(
            Intent service, int flags, Executor executor, ServiceConnection conn) {
        return bindServiceCommon(service, conn, Integer.toUnsignedLong(flags), null, null, executor,
                getUser());
    }

    @Override
    public boolean bindService(Intent service, @NonNull BindServiceFlags flags, Executor executor,
            ServiceConnection conn) {
        return bindServiceCommon(service, conn, flags.getValue(), null, null, executor,
                getUser());
    }

    @Override
    public boolean bindIsolatedService(Intent service, int flags, String instanceName,
            Executor executor, ServiceConnection conn) {
        warnIfCallingFromSystemProcess();
        if (instanceName == null) {
            throw new NullPointerException("null instanceName");
        }
        return bindServiceCommon(service, conn, Integer.toUnsignedLong(flags), instanceName, null, executor,
                getUser());
    }

    @Override
    public boolean bindIsolatedService(Intent service, @NonNull BindServiceFlags flags,
            String instanceName, Executor executor, ServiceConnection conn) {
        warnIfCallingFromSystemProcess();
        if (instanceName == null) {
            throw new NullPointerException("null instanceName");
        }
        return bindServiceCommon(service, conn, flags.getValue(), instanceName, null, executor,
                getUser());
    }

    @Override
    public boolean bindServiceAsUser(Intent service, ServiceConnection conn, int flags,
            UserHandle user) {
        return bindServiceCommon(service, conn, Integer.toUnsignedLong(flags), null,
                mMainThread.getHandler(), null, user);
    }

    @Override
    public boolean bindServiceAsUser(Intent service, ServiceConnection conn,
            @NonNull BindServiceFlags flags, UserHandle user) {
        return bindServiceCommon(service, conn, flags.getValue(), null,
                mMainThread.getHandler(), null, user);
    }

    /** @hide */
    @Override
    public boolean bindServiceAsUser(Intent service, ServiceConnection conn, int flags,
            Handler handler, UserHandle user) {
        if (handler == null) {
            throw new IllegalArgumentException("handler must not be null.");
        }
        return bindServiceCommon(service, conn, Integer.toUnsignedLong(flags), null, handler,
                null, user);
    }

    @Override
    public boolean bindServiceAsUser(Intent service, ServiceConnection conn,
            @NonNull BindServiceFlags flags, Handler handler, UserHandle user) {
        if (handler == null) {
            throw new IllegalArgumentException("handler must not be null.");
        }
        return bindServiceCommon(service, conn, flags.getValue(), null, handler,
                null, user);
    }

    /** @hide */
    @Override
    public IServiceConnection getServiceDispatcher(ServiceConnection conn, Handler handler,
            long flags) {
        return mPackageInfo.getServiceDispatcher(conn, getOuterContext(), handler, flags);
    }

    /** @hide */
    @Override
    public IApplicationThread getIApplicationThread() {
        return mMainThread.getApplicationThread();
    }

    /** @hide */
    @NonNull
    @Override
    public IBinder getProcessToken() {
        return getIApplicationThread().asBinder();
    }

    /** @hide */
    @Override
    public Handler getMainThreadHandler() {
        return mMainThread.getHandler();
    }

    private boolean bindServiceCommon(Intent service, ServiceConnection conn, long flags,
            String instanceName, Handler handler, Executor executor, UserHandle user) {
        // Keep this in sync with DevicePolicyManager.bindDeviceAdminServiceAsUser and
        // ActivityManagerLocal.bindSdkSandboxService
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
            int res = ActivityManager.getService().bindServiceInstance(
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
        if (vmIncorrectContextUseEnabled()) {
            // Check incorrect Context usage.
            if (WINDOW_SERVICE.equals(name) && !isUiContext()) {
                final String errorMessage = "Tried to access visual service "
                        + SystemServiceRegistry.getSystemServiceClassName(name)
                        + " from a non-visual Context:" + getOuterContext();
                final String message = "WindowManager should be accessed from Activity or other "
                        + "visual Context. Use an Activity or a Context created with "
                        + "Context#createWindowContext(int, Bundle), which are adjusted to "
                        + "the configuration and visual bounds of an area on screen.";
                final Exception exception = new IllegalAccessException(errorMessage);
                StrictMode.onIncorrectContextUsed(message, exception);
                Log.e(TAG, errorMessage + " " + message, exception);
            }
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
        switch (mContextType) {
            case CONTEXT_TYPE_ACTIVITY:
            case CONTEXT_TYPE_WINDOW_CONTEXT:
            case CONTEXT_TYPE_SYSTEM_OR_SYSTEM_UI:
                return true;
            case CONTEXT_TYPE_DISPLAY_CONTEXT:
            case CONTEXT_TYPE_NON_UI: {
                return false;
            }
            default:
                return false;
        }
    }

    /** @hide */
    @Override
    public boolean isConfigurationContext() {
        return isUiContext() || mIsConfigurationBasedContext;
    }

    /**
     * Temporary workaround to permit incorrect usages of Context by SystemUI.
     * TODO(b/147647877): Fix usages and remove.
     */
    @SuppressWarnings("AndroidFrameworkClientSidePermissionCheck")
    private static boolean isSystemOrSystemUI(Context context) {
        return ActivityThread.isSystem() || context.checkPermission(
                "android.permission.STATUS_BAR_SERVICE",
                Binder.getCallingPid(),
                Binder.getCallingUid()) == PERMISSION_GRANTED;
    }

    @Override
    public int checkPermission(String permission, int pid, int uid) {
        if (permission == null) {
            throw new IllegalArgumentException("permission is null");
        }
        if (mParams.isRenouncedPermission(permission)
                && pid == android.os.Process.myPid() && uid == android.os.Process.myUid()) {
            Log.v(TAG, "Treating renounced permission " + permission + " as denied");
            return PERMISSION_DENIED;
        }

        // When checking a device-aware permission on a remote device, if the permission is CAMERA
        // or RECORD_AUDIO we need to check remote device's corresponding capability. If the remote
        // device doesn't have capability fall back to checking permission on the default device.
        // Note: we only perform permission check redirection when the device id is not explicitly
        // set in the context.
        int deviceId = getDeviceId();
        if (deviceId != Context.DEVICE_ID_DEFAULT
                && !mIsExplicitDeviceId
                && PermissionManager.DEVICE_AWARE_PERMISSIONS.contains(permission)) {
            VirtualDeviceManager virtualDeviceManager =
                    getSystemService(VirtualDeviceManager.class);
            if (virtualDeviceManager == null) {
                Slog.e(
                        TAG,
                        "VDM is not enabled when device id is not default. deviceId = "
                                + deviceId);
            } else {
                VirtualDevice virtualDevice = virtualDeviceManager.getVirtualDevice(deviceId);
                if (virtualDevice != null) {
                    if ((Objects.equals(permission, Manifest.permission.RECORD_AUDIO)
                                    && !virtualDevice.hasCustomAudioInputSupport())
                            || (Objects.equals(permission, Manifest.permission.CAMERA)
                                    && !virtualDevice.hasCustomCameraSupport())) {
                        deviceId = Context.DEVICE_ID_DEFAULT;
                    }
                } else {
                    Slog.e(
                            TAG,
                            "virtualDevice is not found when device id is not default. deviceId = "
                                    + deviceId);
                }
            }
        }

        return PermissionManager.checkPermission(permission, pid, uid, deviceId);
    }

    /** @hide */
    @Override
    public int checkPermission(String permission, int pid, int uid, IBinder callerToken) {
        if (permission == null) {
            throw new IllegalArgumentException("permission is null");
        }
        if (mParams.isRenouncedPermission(permission)
                && pid == android.os.Process.myPid() && uid == android.os.Process.myUid()) {
            Log.v(TAG, "Treating renounced permission " + permission + " as denied");
            return PERMISSION_DENIED;
        }
        return checkPermission(permission, pid, uid);
    }

    @Override
    public void revokeSelfPermissionsOnKill(@NonNull Collection<String> permissions) {
        getSystemService(PermissionControllerManager.class).revokeSelfPermissionsOnKill(
                getPackageName(), new ArrayList<String>(permissions));
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
        if (mParams.isRenouncedPermission(permission)) {
            Log.v(TAG, "Treating renounced permission " + permission + " as denied");
            return PERMISSION_DENIED;
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

    @Override
    public int checkContentUriPermissionFull(Uri uri, int pid, int uid, int modeFlags) {
        try {
            return ActivityManager.getService().checkContentUriPermissionFull(
                    ContentProvider.getUriWithoutUserId(uri), pid, uid, modeFlags,
                    resolveUserId(uri));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @NonNull
    @Override
    public int[] checkUriPermissions(@NonNull List<Uri> uris, int pid, int uid,
            int modeFlags) {
        try {
            return ActivityManager.getService().checkUriPermissions(uris, pid, uid, modeFlags,
                    getUserId(), null);
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

    @NonNull
    @Override
    public int[] checkCallingUriPermissions(@NonNull List<Uri> uris, int modeFlags) {
        int pid = Binder.getCallingPid();
        if (pid != Process.myPid()) {
            return checkUriPermissions(uris, pid, Binder.getCallingUid(), modeFlags);
        }
        int[] res = new int[uris.size()];
        Arrays.fill(res, PERMISSION_DENIED);
        return res;
    }

    @Override
    public int checkCallingOrSelfUriPermission(Uri uri, int modeFlags) {
        return checkUriPermission(uri, Binder.getCallingPid(),
                Binder.getCallingUid(), modeFlags);
    }

    @NonNull
    @Override
    public int[] checkCallingOrSelfUriPermissions(@NonNull List<Uri> uris, int modeFlags) {
        return checkUriPermissions(uris, Binder.getCallingPid(), Binder.getCallingUid(), modeFlags);
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
            @Nullable Integer overrideDisplayId, Configuration overrideConfig,
            CompatibilityInfo compatInfo, List<ResourcesLoader> resourcesLoader) {
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
                pi.getOverlayPaths(),
                pi.getApplicationInfo().sharedLibraryFiles,
                overrideDisplayId,
                overrideConfig,
                compatInfo,
                classLoader,
                resourcesLoader);
    }

    @Override
    public Context createApplicationContext(ApplicationInfo application, int flags)
            throws NameNotFoundException {
        final UserHandle user = new UserHandle(UserHandle.getUserId(application.uid));
        return createApplicationContextAsUser(application, flags, user);
    }

    private Context createApplicationContextAsUser(ApplicationInfo application, int flags,
                                                   UserHandle user) throws NameNotFoundException {
        LoadedApk pi = mMainThread.getPackageInfo(application, mResources.getCompatibilityInfo(),
                flags | CONTEXT_REGISTER_PACKAGE);
        if (pi != null) {
            ContextImpl c = new ContextImpl(this, mMainThread, pi, ContextParams.EMPTY,
                    mAttributionSource.getAttributionTag(), mAttributionSource.getNext(), null,
                    mToken, user, flags, null,
                    null, mDeviceId, mIsExplicitDeviceId);

            final int displayId = getDisplayId();
            final Integer overrideDisplayId = mForceDisplayOverrideInResources
                    ? displayId : null;

            c.setResources(createResources(mToken, pi, null, overrideDisplayId, null,
                    getDisplayAdjustments(displayId).getCompatibilityInfo(), null));
            if (c.mResources != null) {
                return c;
            }
        }

        throw new PackageManager.NameNotFoundException(
                "Application package " + application.packageName + " not found");
    }

    @Override
    public Context createContextForSdkInSandbox(ApplicationInfo sdkInfo, int flags)
            throws NameNotFoundException {
        if (!Process.isSdkSandbox()) {
            throw new SecurityException("API can only be called from SdkSandbox process");
        }

        final UserHandle user = sdkInfo.uid >= 0
                ? new UserHandle(UserHandle.getUserId(sdkInfo.uid)) : Process.myUserHandle();
        ContextImpl ctx = (ContextImpl) createApplicationContextAsUser(sdkInfo, flags, user);

        // Set sandbox app's context as the application context for sdk context
        ctx.mPackageInfo.makeApplicationInner(/*forceDefaultAppClass=*/false,
                /*instrumentation=*/null);

        return ctx;
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
            return new ContextImpl(this, mMainThread, mPackageInfo, mParams,
                    mAttributionSource.getAttributionTag(), mAttributionSource.getNext(), null,
                    mToken, user, flags, null, null, mDeviceId, mIsExplicitDeviceId);
        }

        LoadedApk pi = mMainThread.getPackageInfo(packageName, mResources.getCompatibilityInfo(),
                flags | CONTEXT_REGISTER_PACKAGE, user.getIdentifier());
        if (pi != null) {
            ContextImpl c = new ContextImpl(this, mMainThread, pi, mParams,
                    mAttributionSource.getAttributionTag(), mAttributionSource.getNext(), null,
                    mToken, user, flags, null, null, mDeviceId, mIsExplicitDeviceId);

            final int displayId = getDisplayId();
            final Integer overrideDisplayId = mForceDisplayOverrideInResources
                    ? displayId : null;

            c.setResources(createResources(mToken, pi, null, overrideDisplayId, null,
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
            throw new IllegalStateException("Own package not found for user "
                    + user.getIdentifier() + ": package=" + getPackageName());
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

        final ContextImpl context = new ContextImpl(this, mMainThread, mPackageInfo, mParams,
                mAttributionSource.getAttributionTag(), mAttributionSource.getNext(), splitName,
                mToken, mUser, mFlags, classLoader, null, mDeviceId, mIsExplicitDeviceId);

        context.setResources(ResourcesManager.getInstance().getResources(
                mToken,
                mPackageInfo.getResDir(),
                paths,
                mPackageInfo.getOverlayDirs(),
                mPackageInfo.getOverlayPaths(),
                mPackageInfo.getApplicationInfo().sharedLibraryFiles,
                mForceDisplayOverrideInResources ? getDisplayId() : null,
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

        if (mForceDisplayOverrideInResources) {
            // Ensure the resources display metrics are adjusted to match the display this context
            // is based on.
            Configuration displayAdjustedConfig = new Configuration();
            displayAdjustedConfig.setTo(mDisplay.getDisplayAdjustments().getConfiguration(),
                    ActivityInfo.CONFIG_WINDOW_CONFIGURATION, 1);
            displayAdjustedConfig.updateFrom(overrideConfiguration);
            overrideConfiguration = displayAdjustedConfig;
        }

        ContextImpl context = new ContextImpl(this, mMainThread, mPackageInfo, mParams,
                mAttributionSource.getAttributionTag(), mAttributionSource.getNext(), mSplitName,
                mToken, mUser, mFlags, mClassLoader, null, mDeviceId,
                mIsExplicitDeviceId);
        context.mIsConfigurationBasedContext = true;

        final int displayId = getDisplayId();
        final Integer overrideDisplayId = mForceDisplayOverrideInResources
                ? displayId : null;
        context.setResources(createResources(mToken, mPackageInfo, mSplitName, overrideDisplayId,
                overrideConfiguration, getDisplayAdjustments(displayId).getCompatibilityInfo(),
                mResources.getLoaders()));
        return context;
    }

    @Override
    public Context createDisplayContext(Display display) {
        if (display == null) {
            throw new IllegalArgumentException("display must not be null");
        }

        ContextImpl context = new ContextImpl(this, mMainThread, mPackageInfo, mParams,
                mAttributionSource.getAttributionTag(), mAttributionSource.getNext(), mSplitName,
                mToken, mUser, mFlags, mClassLoader, null, mDeviceId, mIsExplicitDeviceId);

        final int displayId = display.getDisplayId();

        // Ensure the resources display metrics are adjusted to match the provided display.
        Configuration overrideConfig = new Configuration();
        overrideConfig.setTo(display.getDisplayAdjustments().getConfiguration(),
                ActivityInfo.CONFIG_WINDOW_CONFIGURATION, 1);

        context.setResources(createResources(mToken, mPackageInfo, mSplitName, displayId,
                overrideConfig, display.getDisplayAdjustments().getCompatibilityInfo(),
                mResources.getLoaders()));
        context.setDisplay(display);
        // Inherit context type if the container is from System or System UI context to bypass
        // UI context check.
        context.mContextType = mContextType == CONTEXT_TYPE_SYSTEM_OR_SYSTEM_UI
                ? CONTEXT_TYPE_SYSTEM_OR_SYSTEM_UI : CONTEXT_TYPE_DISPLAY_CONTEXT;
        // Display contexts and any context derived from a display context should always override
        // the display that would otherwise be inherited from mToken (or the global configuration if
        // mToken is null).
        context.mForceDisplayOverrideInResources = true;
        // The configuration is overridden by display adjustments' configuration and won't receive
        // configuration changes. This context won't be regarded as having the proper configuration
        // anymore.
        context.mIsConfigurationBasedContext = false;
        return context;
    }

    private void setDisplay(Display display) {
        mDisplay = display;
        if (display != null) {
            updateDeviceIdIfChanged(display.getDisplayId());
        }
    }

    @Override
    public @NonNull Context createDeviceContext(int deviceId) {
        if (deviceId != Context.DEVICE_ID_DEFAULT) {
            VirtualDeviceManager vdm = getSystemService(VirtualDeviceManager.class);
            if (vdm == null || !vdm.isValidVirtualDeviceId(deviceId)) {
                throw new IllegalArgumentException(
                        "Not a valid ID of the default device or any virtual device: " + deviceId);
            }
        }

        return new ContextImpl(this, mMainThread, mPackageInfo, mParams,
                mAttributionSource.getAttributionTag(), mAttributionSource.getNext(), mSplitName,
                mToken, mUser, mFlags, mClassLoader, null, deviceId, true);
    }

    @NonNull
    @Override
    public WindowContext createWindowContext(@WindowType int type,
            @Nullable Bundle options) {
        if (getDisplay() == null) {
            throw new UnsupportedOperationException("Please call this API with context associated"
                    + " with a display instance, such as Activity or context created via"
                    + " Context#createDisplayContext(Display), or try to invoke"
                    + " Context#createWindowContext(Display, int, Bundle)");
        }
        return createWindowContextInternal(getDisplay(), type, options);
    }

    @NonNull
    @Override
    public WindowContext createWindowContext(@NonNull Display display, @WindowType int type,
            @Nullable Bundle options) {
        if (display == null) {
            throw new IllegalArgumentException("Display must not be null");
        }
        return createWindowContextInternal(display, type, options);
    }

    /**
     * The internal implementation of {@link Context#createWindowContext(int, Bundle)} and
     * {@link Context#createWindowContext(Display, int, Bundle)}.
     *
     * @param display The {@link Display} instance to be associated with.
     *
     * @see Context#createWindowContext(Display, int, Bundle)
     * @see Context#createWindowContext(int, Bundle)
     */
    private WindowContext createWindowContextInternal(@NonNull Display display,
            @WindowType int type, @Nullable Bundle options) {
        // Step 1. Create a WindowTokenClient to associate with the WindowContext's Resources
        //         instance and it will be later used to receive configuration updates from the
        //         server side.
        final WindowTokenClient windowTokenClient = new WindowTokenClient();

        // Step 2. Create the base context of the window context, it will also create a Resources
        //         associated with the WindowTokenClient and set the token to the base context.
        final ContextImpl windowContextBase = createWindowContextBase(windowTokenClient,
                display.getDisplayId());

        // Step 3. Create a WindowContext instance and set it as the outer context of the base
        //         context to make the service obtained by #getSystemService(String) able to query
        //         the WindowContext's WindowManager instead of the default one.
        final WindowContext windowContext = new WindowContext(windowContextBase, type, options);
        windowContextBase.setOuterContext(windowContext);

        // Step 4. Attach the WindowContext to the WindowTokenClient. In this way, when there's a
        //         configuration update from the server side, the update will then apply to
        //         WindowContext's resources.
        windowTokenClient.attachContext(windowContext);

        // Step 5. Associate the WindowContext's token to a DisplayArea.
        windowContext.attachToDisplayArea();

        return windowContext;
    }

    @NonNull
    @Override
    public Context createTokenContext(@NonNull IBinder token, @NonNull Display display) {
        if (display == null) {
            throw new IllegalArgumentException("Display must not be null");
        }
        return createWindowContextBase(token, display.getDisplayId());
    }

    /**
     * Creates the base {@link Context} for UI context to associate with a non-{@link Activity}
     * window.
     *
     * @param token The token to associate with {@link Resources}
     * @param displayId The ID of {@link Display} to associate with.
     *
     * @see #createWindowContext(Display, int, Bundle)
     * @see #createTokenContext(IBinder, Display)
     */
    @UiContext
    ContextImpl createWindowContextBase(@NonNull IBinder token, int displayId) {
        ContextImpl baseContext = new ContextImpl(this, mMainThread, mPackageInfo, mParams,
                mAttributionSource.getAttributionTag(), mAttributionSource.getNext(), mSplitName,
                token, mUser, mFlags, mClassLoader, null, mDeviceId, mIsExplicitDeviceId);
        // Window contexts receive configurations directly from the server and as such do not
        // need to override their display in ResourcesManager.
        baseContext.mForceDisplayOverrideInResources = false;
        baseContext.mContextType = CONTEXT_TYPE_WINDOW_CONTEXT;

        final Resources windowContextResources = createWindowContextResources(baseContext);
        baseContext.setResources(windowContextResources);
        // Associate the display with window context resources so that configuration update from
        // the server side will also apply to the display's metrics.
        baseContext.setDisplay(ResourcesManager.getInstance().getAdjustedDisplay(
                displayId, windowContextResources));

        return baseContext;
    }

    /**
     * Creates the {@link Resources} to associate with the {@link WindowContext}'s token.
     *
     * When there's a {@link Configuration} update, this Resources instance will be updated to match
     * the new configuration.
     *
     * @see WindowTokenClient
     * @see #getWindowContextToken()
     */
    private static Resources createWindowContextResources(@NonNull ContextImpl windowContextBase) {
        final LoadedApk packageInfo = windowContextBase.mPackageInfo;
        final ClassLoader classLoader = windowContextBase.getClassLoader();
        final IBinder token = windowContextBase.getWindowContextToken();

        final String resDir = packageInfo.getResDir();
        final String[] splitResDirs = packageInfo.getSplitResDirs();
        final String[] legacyOverlayDirs = packageInfo.getOverlayDirs();
        final String[] overlayPaths = packageInfo.getOverlayPaths();
        final String[] libDirs = packageInfo.getApplicationInfo().sharedLibraryFiles;
        final int displayId = windowContextBase.getDisplayId();
        final CompatibilityInfo compatInfo = (displayId == Display.DEFAULT_DISPLAY)
                ? packageInfo.getCompatibilityInfo()
                : CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO;
        final List<ResourcesLoader> loaders = windowContextBase.mResources.getLoaders();

        return windowContextBase.mResourcesManager.createBaseTokenResources(token, resDir,
                splitResDirs, legacyOverlayDirs, overlayPaths, libDirs, displayId,
                null /* overrideConfig */, compatInfo, classLoader, loaders);
    }

    @NonNull
    @Override
    public Context createContext(@NonNull ContextParams contextParams) {
        return new ContextImpl(this, mMainThread, mPackageInfo, contextParams,
                contextParams.getAttributionTag(), contextParams.getNextAttributionSource(),
                mSplitName, mToken, mUser, mFlags, mClassLoader, null, mDeviceId,
                mIsExplicitDeviceId);
    }

    @Override
    public @NonNull Context createAttributionContext(@Nullable String attributionTag) {
        return createContext(
                new ContextParams.Builder(mParams).setAttributionTag(attributionTag).build());
    }

    @Override
    public Context createDeviceProtectedStorageContext() {
        final int flags = (mFlags & ~Context.CONTEXT_CREDENTIAL_PROTECTED_STORAGE)
                | Context.CONTEXT_DEVICE_PROTECTED_STORAGE;
        return new ContextImpl(this, mMainThread, mPackageInfo, mParams,
                mAttributionSource.getAttributionTag(), mAttributionSource.getNext(), mSplitName,
                mToken, mUser, flags, mClassLoader, null, mDeviceId, mIsExplicitDeviceId);
    }

    @Override
    public Context createCredentialProtectedStorageContext() {
        final int flags = (mFlags & ~Context.CONTEXT_DEVICE_PROTECTED_STORAGE)
                | Context.CONTEXT_CREDENTIAL_PROTECTED_STORAGE;
        return new ContextImpl(this, mMainThread, mPackageInfo, mParams,
                mAttributionSource.getAttributionTag(), mAttributionSource.getNext(), mSplitName,
                mToken, mUser, flags, mClassLoader, null, mDeviceId, mIsExplicitDeviceId);
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
        if (!isAssociatedWithDisplay()) {
            throw new UnsupportedOperationException("Tried to obtain display from a Context not "
                    + "associated with one. Only visual Contexts (such as Activity or one created "
                    + "with Context#createWindowContext) or ones created with "
                    + "Context#createDisplayContext are associated with displays. Other types of "
                    + "Contexts are typically related to background entities and may return an "
                    + "arbitrary display.");
        }
        return getDisplayNoVerify();
    }

    private boolean isAssociatedWithDisplay() {
        switch (mContextType) {
            case CONTEXT_TYPE_DISPLAY_CONTEXT:
            case CONTEXT_TYPE_ACTIVITY:
            case CONTEXT_TYPE_WINDOW_CONTEXT:
            // TODO(b/170369943): Remove after WindowContext migration
            case CONTEXT_TYPE_SYSTEM_OR_SYSTEM_UI:
                return true;
            default:
                return false;
        }
    }

    /**
     * @hide
     */
    @Override
    public int getAssociatedDisplayId()  {
        return isAssociatedWithDisplay() ? getDisplayId() : Display.INVALID_DISPLAY;
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
        setDisplay(mResourcesManager.getAdjustedDisplay(displayId, mResources));
        if (mContextType == CONTEXT_TYPE_NON_UI) {
            mContextType = CONTEXT_TYPE_DISPLAY_CONTEXT;
        }
    }

    private void updateDeviceIdIfChanged(int displayId) {
        if (mIsExplicitDeviceId) {
            return;
        }

        if ((displayId == Display.DEFAULT_DISPLAY || displayId == Display.INVALID_DISPLAY)
                && mDeviceId == DEVICE_ID_DEFAULT) {
            // DEFAULT_DISPLAY & INVALID_DISPLAY are associated with default device.
            // Return early avoiding instantiating VDM when it's not needed.
            return;
        }

        VirtualDeviceManager vdm = getSystemService(VirtualDeviceManager.class);
        if (vdm != null) {
            int deviceId = vdm.getDeviceIdForDisplayId(displayId);
            if (deviceId != mDeviceId) {
                mDeviceId = deviceId;
                mAttributionSource =
                        createAttributionSourceWithDeviceId(mAttributionSource, mDeviceId);
                notifyOnDeviceChangedListeners(mDeviceId);
            }
        }
    }

    @Override
    public void updateDeviceId(int updatedDeviceId) {
        if (updatedDeviceId != Context.DEVICE_ID_DEFAULT) {
            VirtualDeviceManager vdm = getSystemService(VirtualDeviceManager.class);
            if (vdm == null) {
                throw new IllegalArgumentException(
                        "VDM is not enabled when updating to non-default device id: "
                                + updatedDeviceId);
            }
            if (!vdm.isValidVirtualDeviceId(updatedDeviceId)) {
                throw new IllegalArgumentException(
                        "Not a valid ID of the default device or any virtual device: "
                                + updatedDeviceId);
            }
        }
        if (mIsExplicitDeviceId) {
            throw new UnsupportedOperationException(
                    "Cannot update device ID on a Context created with createDeviceContext()");
        }

        if (mDeviceId != updatedDeviceId) {
            mDeviceId = updatedDeviceId;
            mAttributionSource = createAttributionSourceWithDeviceId(mAttributionSource, mDeviceId);
            notifyOnDeviceChangedListeners(updatedDeviceId);
        }
    }

    @Override
    public int getDeviceId() {
        return mDeviceId;
    }

    @Override
    public void registerDeviceIdChangeListener(@NonNull @CallbackExecutor Executor executor,
            @NonNull IntConsumer listener) {
        Objects.requireNonNull(executor, "executor cannot be null");
        Objects.requireNonNull(listener, "listener cannot be null");

        synchronized (mDeviceIdListenerLock) {
            if (getDeviceIdListener(listener) != null) {
                throw new IllegalArgumentException(
                        "attempt to call registerDeviceIdChangeListener() "
                                + "on a previously registered listener");
            }
            // lazy initialization
            if (mDeviceIdChangeListeners == null) {
                mDeviceIdChangeListeners = new ArrayList<>();
            }
            mDeviceIdChangeListeners.add(new DeviceIdChangeListenerDelegate(listener, executor));
        }
    }

    @Override
    public void unregisterDeviceIdChangeListener(@NonNull IntConsumer listener) {
        Objects.requireNonNull(listener, "listener cannot be null");
        synchronized (mDeviceIdListenerLock) {
            DeviceIdChangeListenerDelegate listenerToRemove = getDeviceIdListener(listener);
            if (listenerToRemove != null) {
                mDeviceIdChangeListeners.remove(listenerToRemove);
            }
        }
    }

    @GuardedBy("mDeviceIdListenerLock")
    @Nullable
    private DeviceIdChangeListenerDelegate getDeviceIdListener(
            @Nullable IntConsumer listener) {
        if (mDeviceIdChangeListeners == null) {
            return null;
        }
        for (int i = 0; i < mDeviceIdChangeListeners.size(); i++) {
            DeviceIdChangeListenerDelegate delegate = mDeviceIdChangeListeners.get(i);
            if (delegate.mListener == listener) {
                return delegate;
            }
        }
        return null;
    }

    private void notifyOnDeviceChangedListeners(int deviceId) {
        synchronized (mDeviceIdListenerLock) {
            if (mDeviceIdChangeListeners != null) {
                for (DeviceIdChangeListenerDelegate delegate : mDeviceIdChangeListeners) {
                    delegate.mExecutor.execute(() ->
                            delegate.mListener.accept(deviceId));
                }
            }
        }
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

    @Override
    protected void finalize() throws Throwable {
        // If mToken is a WindowTokenClient, the Context is usually associated with a
        // WindowContainer. We should detach from WindowContainer when the Context is finalized
        // if this Context is not a WindowContext. WindowContext finalization is handled in
        // WindowContext class.
        if (mToken instanceof WindowTokenClient && mOwnsToken) {
            WindowTokenClientController.getInstance().detachIfNeeded(
                    (WindowTokenClient) mToken);
        }
        super.finalize();
    }

    @UnsupportedAppUsage
    static ContextImpl createSystemContext(ActivityThread mainThread) {
        LoadedApk packageInfo = new LoadedApk(mainThread);
        ContextImpl context = new ContextImpl(null, mainThread, packageInfo,
                ContextParams.EMPTY, null, null, null, null, null, 0, null, null,
                DEVICE_ID_DEFAULT, false);
        context.setResources(packageInfo.getResources());
        context.mResources.updateConfiguration(context.mResourcesManager.getConfiguration(),
                context.mResourcesManager.getDisplayMetrics());
        context.mContextType = CONTEXT_TYPE_SYSTEM_OR_SYSTEM_UI;
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
        final WindowTokenClient token = new WindowTokenClient();
        final ContextImpl context = systemContext.createWindowContextBase(token, displayId);
        token.attachContext(context);
        WindowTokenClientController.getInstance().attachToDisplayContent(token, displayId);
        context.mContextType = CONTEXT_TYPE_SYSTEM_OR_SYSTEM_UI;
        context.mOwnsToken = true;

        return context;
    }

    @UnsupportedAppUsage
    static ContextImpl createAppContext(ActivityThread mainThread, LoadedApk packageInfo) {
        return createAppContext(mainThread, packageInfo, null);
    }

    static ContextImpl createAppContext(ActivityThread mainThread, LoadedApk packageInfo,
            String opPackageName) {
        if (packageInfo == null) throw new IllegalArgumentException("packageInfo");
        ContextImpl context = new ContextImpl(null, mainThread, packageInfo,
                ContextParams.EMPTY, null, null, null, null, null, 0, null, opPackageName,
                DEVICE_ID_DEFAULT, false);
        context.setResources(packageInfo.getResources());
        context.mContextType = isSystemOrSystemUI(context) ? CONTEXT_TYPE_SYSTEM_OR_SYSTEM_UI
                : CONTEXT_TYPE_NON_UI;
        return context;
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
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

        final String attributionTag;
        if (activityInfo.attributionTags != null && activityInfo.attributionTags.length > 0) {
            attributionTag = activityInfo.attributionTags[0];
        } else {
            attributionTag = null;
        }

        ContextImpl context = new ContextImpl(null, mainThread, packageInfo, ContextParams.EMPTY,
                attributionTag, null, activityInfo.splitName, activityToken, null, 0, classLoader,
                null, DEVICE_ID_DEFAULT, false);
        context.mContextType = CONTEXT_TYPE_ACTIVITY;
        context.mIsConfigurationBasedContext = true;

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
                packageInfo.getOverlayPaths(),
                packageInfo.getApplicationInfo().sharedLibraryFiles,
                displayId,
                overrideConfiguration,
                compatInfo,
                classLoader,
                packageInfo.getApplication() == null ? null
                        : packageInfo.getApplication().getResources().getLoaders()));
        context.setDisplay(resourcesManager.getAdjustedDisplay(
                displayId, context.getResources()));
        return context;
    }

    private ContextImpl(@Nullable ContextImpl container, @NonNull ActivityThread mainThread,
            @NonNull LoadedApk packageInfo, @NonNull ContextParams params,
            @Nullable String attributionTag, @Nullable AttributionSource nextAttributionSource,
            @Nullable String splitName, @Nullable IBinder token, @Nullable UserHandle user,
            int flags, @Nullable ClassLoader classLoader, @Nullable String overrideOpPackageName,
            int deviceId, boolean isExplicitDeviceId) {
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
        mToken = token;
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

        mDeviceId = deviceId;
        mIsExplicitDeviceId = isExplicitDeviceId;

        if (container != null) {
            mBasePackageName = container.mBasePackageName;
            opPackageName = container.mOpPackageName;
            setResources(container.mResources);
            mDisplay = container.mDisplay;
            if (!isExplicitDeviceId) {
                mIsExplicitDeviceId = container.mIsExplicitDeviceId;
                mDeviceId = container.mDeviceId;
            }
            mForceDisplayOverrideInResources = container.mForceDisplayOverrideInResources;
            mIsConfigurationBasedContext = container.mIsConfigurationBasedContext;
            mContextType = container.mContextType;
            mContentCaptureOptions = container.mContentCaptureOptions;
            mAutofillOptions = container.mAutofillOptions;
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
        mParams = Objects.requireNonNull(params);
        mAttributionSource = createAttributionSource(attributionTag, nextAttributionSource,
                params.getRenouncedPermissions(), params.shouldRegisterAttributionSource(), mDeviceId);
        mContentResolver = new ApplicationContentResolver(this, mainThread);
    }

    private @NonNull AttributionSource createAttributionSource(@Nullable String attributionTag,
            @Nullable AttributionSource nextAttributionSource,
            @Nullable Set<String> renouncedPermissions, boolean shouldRegister,
            int deviceId) {
        AttributionSource attributionSource = new AttributionSource(Process.myUid(),
                Process.myPid(), mOpPackageName, attributionTag,
                (renouncedPermissions != null) ? renouncedPermissions.toArray(new String[0]) : null,
                deviceId, nextAttributionSource);
        // If we want to access protected data on behalf of another app we need to
        // tell the OS that we opt in to participate in the attribution chain.
        return registerAttributionSourceIfNeeded(attributionSource, shouldRegister);
    }

    private @NonNull AttributionSource createAttributionSourceWithDeviceId(
            @NonNull AttributionSource oldSource, int deviceId) {
        boolean shouldRegister = false;
        if (shouldRegisterAttributionSource()) {
            shouldRegister = mParams.shouldRegisterAttributionSource();
        }
        return registerAttributionSourceIfNeeded(oldSource.withDeviceId(deviceId), shouldRegister);
    }

    private @NonNull AttributionSource registerAttributionSourceIfNeeded(
            @NonNull AttributionSource attributionSource, boolean shouldRegister) {
        if (shouldRegister || attributionSource.getNext() != null) {
            return getSystemService(PermissionManager.class)
                    .registerAttributionSource(attributionSource);
        }
        return attributionSource;
    }

    void setResources(Resources r) {
        if (r instanceof CompatResources) {
            ((CompatResources) r).setContext(this);
        }
        mResources = r;

        if (r != null) {
            // only do this if the user already has more than one preferred locale
            if (android.content.res.Flags.defaultLocale()
                    && r.getConfiguration().getLocales().size() > 1) {
                LocaleConfig lc = LocaleConfig.fromContextIgnoringOverride(this);
                mResourcesManager.setLocaleConfig(lc);
            }
        }
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
        if (mContextType == CONTEXT_TYPE_SYSTEM_OR_SYSTEM_UI
                && mToken instanceof WindowTokenClient) {
            mMainThread.onSystemUiContextCleanup(this);
        }
    }

    @UnsupportedAppUsage
    final Context getReceiverRestrictedContext() {
        if (mReceiverRestrictedContext != null) {
            return mReceiverRestrictedContext;
        }
        return mReceiverRestrictedContext = new ReceiverRestrictedContext(getOuterContext());
    }

    @UnsupportedAppUsage
    final void setOuterContext(@NonNull Context context) {
        mOuterContext = context;
    }

    @UnsupportedAppUsage
    final Context getOuterContext() {
        return mOuterContext;
    }

    @Override
    @UnsupportedAppUsage
    public IBinder getActivityToken() {
        return mContextType == CONTEXT_TYPE_ACTIVITY ? mToken : null;
    }

    @Override
    public IBinder getWindowContextToken() {
        switch (mContextType) {
            case CONTEXT_TYPE_WINDOW_CONTEXT:
            case CONTEXT_TYPE_SYSTEM_OR_SYSTEM_UI:
                return mToken;
            default:
                return null;
        }
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
            if (dir != null && !dir.canWrite()) {
                // Older versions of the MediaProvider mainline module had a rare early boot race
                // condition where app-private dirs could be created with the wrong permissions;
                // fix this up here. This check should be very fast, because dir.exists() above
                // will already have loaded the dentry in the cache.
                sm.fixupAppDir(dir);
            }
            result[i] = dir;
        }
        return result;
    }

    @Override
    public void destroy() {
        // The final clean-up is to release BroadcastReceiver registrations. It is called in
        // ActivityThread for Activity and Service. For the context, such as WindowContext,
        // without lifecycle concept, it should be called once the context is released.
        scheduleFinalCleanup(getClass().getName(), getOuterContext().getClass().getSimpleName());
    }

    @Override
    public void closeSystemDialogs() {
        final Intent intent = new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        final Bundle options = BroadcastOptions.makeBasic()
                .setDeliveryGroupPolicy(BroadcastOptions.DELIVERY_GROUP_POLICY_MOST_RECENT)
                .setDeferralPolicy(BroadcastOptions.DEFERRAL_POLICY_UNTIL_ACTIVE)
                .toBundle();
        sendBroadcast(intent, null /* receiverPermission */, options);
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
