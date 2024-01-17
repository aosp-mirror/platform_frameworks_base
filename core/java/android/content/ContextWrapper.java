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

package android.content;

import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.annotation.UiContext;
import android.app.BroadcastOptions;
import android.app.IApplicationThread;
import android.app.IServiceConnection;
import android.app.compat.CompatChanges;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDatabase.CursorFactory;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.UserHandle;
import android.view.Display;
import android.view.DisplayAdjustments;
import android.view.WindowManager.LayoutParams.WindowType;
import android.view.autofill.AutofillManager.AutofillClient;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.IntConsumer;

/**
 * Proxying implementation of Context that simply delegates all of its calls to
 * another Context.  Can be subclassed to modify behavior without changing
 * the original Context.
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public class ContextWrapper extends Context {
    @UnsupportedAppUsage
    Context mBase;

    /**
     * A list to store {@link ComponentCallbacks} which
     * passes to {@link #registerComponentCallbacks(ComponentCallbacks)} before
     * {@link #attachBaseContext(Context)}.
     * It is to provide compatibility behavior for Application targeted prior to
     * {@link Build.VERSION_CODES#TIRAMISU}.
     *
     * @hide
     */
    @GuardedBy("mLock")
    @VisibleForTesting
    public List<ComponentCallbacks> mCallbacksRegisteredToSuper;

    private final Object mLock = new Object();

    public ContextWrapper(Context base) {
        mBase = base;
    }

    /**
     * Set the base context for this ContextWrapper.  All calls will then be
     * delegated to the base context.  Throws
     * IllegalStateException if a base context has already been set.
     *
     * @param base The new base context for this wrapper.
     */
    protected void attachBaseContext(Context base) {
        if (mBase != null) {
            throw new IllegalStateException("Base context already set");
        }
        mBase = base;
    }

    /**
     * @return the base context as set by the constructor or setBaseContext
     */
    public Context getBaseContext() {
        return mBase;
    }

    @Override
    public AssetManager getAssets() {
        return mBase.getAssets();
    }

    @Override
    public Resources getResources() {
        return mBase.getResources();
    }

    @Override
    public PackageManager getPackageManager() {
        return mBase.getPackageManager();
    }

    @Override
    public ContentResolver getContentResolver() {
        return mBase.getContentResolver();
    }

    @Override
    public Looper getMainLooper() {
        return mBase.getMainLooper();
    }

    @Override
    public Executor getMainExecutor() {
        return mBase.getMainExecutor();
    }

    @Override
    public Context getApplicationContext() {
        return mBase.getApplicationContext();
    }

    @Override
    public void setTheme(int resid) {
        mBase.setTheme(resid);
    }

    /** @hide */
    @Override
    @UnsupportedAppUsage
    public int getThemeResId() {
        return mBase.getThemeResId();
    }

    @Override
    public Resources.Theme getTheme() {
        return mBase.getTheme();
    }

    @Override
    public ClassLoader getClassLoader() {
        return mBase.getClassLoader();
    }

    @Override
    public String getPackageName() {
        return mBase.getPackageName();
    }

    /** @hide */
    @Override
    @UnsupportedAppUsage
    public String getBasePackageName() {
        return mBase.getBasePackageName();
    }

    /** @hide */
    @Override
    public String getOpPackageName() {
        return mBase.getOpPackageName();
    }

    /** @hide */
    @Override
    public @Nullable String getAttributionTag() {
        return mBase.getAttributionTag();
    }

    @Override
    public @Nullable ContextParams getParams() {
        return mBase.getParams();
    }

    @Override
    public ApplicationInfo getApplicationInfo() {
        return mBase.getApplicationInfo();
    }

    @Override
    public String getPackageResourcePath() {
        return mBase.getPackageResourcePath();
    }

    @Override
    public String getPackageCodePath() {
        return mBase.getPackageCodePath();
    }

    @Override
    public SharedPreferences getSharedPreferences(String name, int mode) {
        return mBase.getSharedPreferences(name, mode);
    }

    /** @removed */
    @Override
    public SharedPreferences getSharedPreferences(File file, int mode) {
        return mBase.getSharedPreferences(file, mode);
    }

    /** @hide */
    @Override
    public void reloadSharedPreferences() {
        mBase.reloadSharedPreferences();
    }

    @Override
    public boolean moveSharedPreferencesFrom(Context sourceContext, String name) {
        return mBase.moveSharedPreferencesFrom(sourceContext, name);
    }

    @Override
    public boolean deleteSharedPreferences(String name) {
        return mBase.deleteSharedPreferences(name);
    }

    @Override
    public FileInputStream openFileInput(String name)
            throws FileNotFoundException {
        return mBase.openFileInput(name);
    }

    @Override
    public FileOutputStream openFileOutput(String name, int mode)
            throws FileNotFoundException {
        return mBase.openFileOutput(name, mode);
    }

    @Override
    public boolean deleteFile(String name) {
        return mBase.deleteFile(name);
    }

    @Override
    public File getFileStreamPath(String name) {
        return mBase.getFileStreamPath(name);
    }

    /** @removed */
    @Override
    public File getSharedPreferencesPath(String name) {
        return mBase.getSharedPreferencesPath(name);
    }

    @Override
    public String[] fileList() {
        return mBase.fileList();
    }

    @Override
    public File getDataDir() {
        return mBase.getDataDir();
    }

    @Override
    public File getFilesDir() {
        return mBase.getFilesDir();
    }

    /**
     * {@inheritDoc Context#getCrateDir()}
     * @hide
     */
    @NonNull
    @Override
    public File getCrateDir(@NonNull String cratedId) {
        return mBase.getCrateDir(cratedId);
    }

    @Override
    public File getNoBackupFilesDir() {
        return mBase.getNoBackupFilesDir();
    }

    @Override
    public @Nullable File getExternalFilesDir(@Nullable String type) {
        return mBase.getExternalFilesDir(type);
    }

    @Override
    public File[] getExternalFilesDirs(String type) {
        return mBase.getExternalFilesDirs(type);
    }

    @Override
    public File getObbDir() {
        return mBase.getObbDir();
    }

    @Override
    public File[] getObbDirs() {
        return mBase.getObbDirs();
    }

    @Override
    public File getCacheDir() {
        return mBase.getCacheDir();
    }

    @Override
    public File getCodeCacheDir() {
        return mBase.getCodeCacheDir();
    }

    @Override
    public @Nullable File getExternalCacheDir() {
        return mBase.getExternalCacheDir();
    }

    @Override
    public File[] getExternalCacheDirs() {
        return mBase.getExternalCacheDirs();
    }

    @Override
    public File[] getExternalMediaDirs() {
        return mBase.getExternalMediaDirs();
    }

    @Override
    public File getDir(String name, int mode) {
        return mBase.getDir(name, mode);
    }


    /** @hide **/
    @Override
    public @Nullable File getPreloadsFileCache() {
        return mBase.getPreloadsFileCache();
    }

    @Override
    public SQLiteDatabase openOrCreateDatabase(String name, int mode, CursorFactory factory) {
        return mBase.openOrCreateDatabase(name, mode, factory);
    }

    @Override
    public SQLiteDatabase openOrCreateDatabase(String name, int mode, CursorFactory factory,
            @Nullable DatabaseErrorHandler errorHandler) {
        return mBase.openOrCreateDatabase(name, mode, factory, errorHandler);
    }

    @Override
    public boolean moveDatabaseFrom(Context sourceContext, String name) {
        return mBase.moveDatabaseFrom(sourceContext, name);
    }

    @Override
    public boolean deleteDatabase(String name) {
        return mBase.deleteDatabase(name);
    }

    @Override
    public File getDatabasePath(String name) {
        return mBase.getDatabasePath(name);
    }

    @Override
    public String[] databaseList() {
        return mBase.databaseList();
    }

    @Override
    @Deprecated
    public Drawable getWallpaper() {
        return mBase.getWallpaper();
    }

    @Override
    @Deprecated
    public Drawable peekWallpaper() {
        return mBase.peekWallpaper();
    }

    @Override
    @Deprecated
    public int getWallpaperDesiredMinimumWidth() {
        return mBase.getWallpaperDesiredMinimumWidth();
    }

    @Override
    @Deprecated
    public int getWallpaperDesiredMinimumHeight() {
        return mBase.getWallpaperDesiredMinimumHeight();
    }

    @Override
    @Deprecated
    public void setWallpaper(Bitmap bitmap) throws IOException {
        mBase.setWallpaper(bitmap);
    }

    @Override
    @Deprecated
    public void setWallpaper(InputStream data) throws IOException {
        mBase.setWallpaper(data);
    }

    @Override
    @Deprecated
    public void clearWallpaper() throws IOException {
        mBase.clearWallpaper();
    }

    @Override
    public void startActivity(Intent intent) {
        mBase.startActivity(intent);
    }

    /** @hide */
    @Override
    public void startActivityAsUser(Intent intent, UserHandle user) {
        mBase.startActivityAsUser(intent, user);
    }

    /** @hide **/
    public void startActivityForResult(
            String who, Intent intent, int requestCode, @Nullable Bundle options) {
        mBase.startActivityForResult(who, intent, requestCode, options);
    }

    /** @hide **/
    public boolean canStartActivityForResult() {
        return mBase.canStartActivityForResult();
    }

    @Override
    public void startActivity(Intent intent, @Nullable Bundle options) {
        mBase.startActivity(intent, options);
    }

    /** @hide */
    @Override
    public void startActivityAsUser(Intent intent, @Nullable Bundle options, UserHandle user) {
        mBase.startActivityAsUser(intent, options, user);
    }

    @Override
    public void startActivities(Intent[] intents) {
        mBase.startActivities(intents);
    }

    @Override
    public void startActivities(Intent[] intents, @Nullable Bundle options) {
        mBase.startActivities(intents, options);
    }

    /** @hide */
    @Override
    public int startActivitiesAsUser(Intent[] intents, @Nullable Bundle options,
            UserHandle userHandle) {
        return mBase.startActivitiesAsUser(intents, options, userHandle);
    }

    @Override
    public void startIntentSender(IntentSender intent,
            @Nullable Intent fillInIntent, int flagsMask, int flagsValues,
            int extraFlags)
            throws IntentSender.SendIntentException {
        mBase.startIntentSender(intent, fillInIntent, flagsMask,
                flagsValues, extraFlags);
    }

    @Override
    public void startIntentSender(IntentSender intent,
            @Nullable Intent fillInIntent, int flagsMask, int flagsValues,
            int extraFlags, @Nullable Bundle options)
            throws IntentSender.SendIntentException {
        mBase.startIntentSender(intent, fillInIntent, flagsMask,
                flagsValues, extraFlags, options);
    }

    @Override
    public void sendBroadcast(Intent intent) {
        mBase.sendBroadcast(intent);
    }

    @Override
    public void sendBroadcast(Intent intent, @Nullable String receiverPermission) {
        mBase.sendBroadcast(intent, receiverPermission);
    }

    /** @hide */
    @Override
    public void sendBroadcastMultiplePermissions(@NonNull Intent intent,
            @NonNull String[] receiverPermissions) {
        mBase.sendBroadcastMultiplePermissions(intent, receiverPermissions);
    }

    /** @hide */
    @Override
    public void sendBroadcastMultiplePermissions(@NonNull Intent intent,
            @NonNull String[] receiverPermissions, @Nullable String[] excludedPermissions,
            @Nullable String[] excludedPackages, @Nullable BroadcastOptions options) {
        mBase.sendBroadcastMultiplePermissions(intent, receiverPermissions, excludedPermissions,
                excludedPackages, options);
    }

    /** @hide */
    @Override
    public void sendBroadcastMultiplePermissions(@NonNull Intent intent,
            @NonNull String[] receiverPermissions, @Nullable Bundle options) {
        mBase.sendBroadcastMultiplePermissions(intent, receiverPermissions, options);
    }

    /** @hide */
    @Override
    public void sendBroadcastAsUserMultiplePermissions(Intent intent, UserHandle user,
            String[] receiverPermissions) {
        mBase.sendBroadcastAsUserMultiplePermissions(intent, user, receiverPermissions);
    }

    @Override
    public void sendBroadcast(@NonNull Intent intent, @Nullable String receiverPermission,
            @Nullable Bundle options) {
        mBase.sendBroadcast(intent, receiverPermission, options);
    }

    /** @hide */
    @Override
    public void sendBroadcast(Intent intent, @Nullable String receiverPermission, int appOp) {
        mBase.sendBroadcast(intent, receiverPermission, appOp);
    }

    @Override
    public void sendOrderedBroadcast(Intent intent,
            @Nullable String receiverPermission) {
        mBase.sendOrderedBroadcast(intent, receiverPermission);
    }

    @SuppressLint("AndroidFrameworkRequiresPermission")
    @Override
    public void sendOrderedBroadcast(@NonNull Intent intent,
            @Nullable String receiverPermission,
            @Nullable Bundle options) {
        mBase.sendOrderedBroadcast(intent, receiverPermission, options);
    }

    @Override
    public void sendOrderedBroadcast(
            Intent intent, @Nullable String receiverPermission,
            @Nullable BroadcastReceiver resultReceiver, @Nullable Handler scheduler,
            int initialCode, @Nullable String initialData, @Nullable Bundle initialExtras) {
        mBase.sendOrderedBroadcast(intent, receiverPermission,
                resultReceiver, scheduler, initialCode,
                initialData, initialExtras);
    }

    @Override
    public void sendOrderedBroadcast(
            @NonNull Intent intent, @Nullable String receiverPermission, @Nullable Bundle options,
            @Nullable BroadcastReceiver resultReceiver, @Nullable Handler scheduler,
            int initialCode, @Nullable String initialData, @Nullable Bundle initialExtras) {
        mBase.sendOrderedBroadcast(intent, receiverPermission,
                options, resultReceiver, scheduler, initialCode,
                initialData, initialExtras);
    }

    /** @hide */
    @Override
    public void sendOrderedBroadcast(
            Intent intent, @Nullable String receiverPermission, int appOp,
            @Nullable BroadcastReceiver resultReceiver, @Nullable Handler scheduler,
            int initialCode, @Nullable String initialData, @Nullable Bundle initialExtras) {
        mBase.sendOrderedBroadcast(intent, receiverPermission, appOp,
                resultReceiver, scheduler, initialCode,
                initialData, initialExtras);
    }

    @Override
    public void sendBroadcastAsUser(Intent intent, UserHandle user) {
        mBase.sendBroadcastAsUser(intent, user);
    }

    @Override
    public void sendBroadcastAsUser(Intent intent, UserHandle user,
            String receiverPermission) {
        mBase.sendBroadcastAsUser(intent, user, receiverPermission);
    }

    /** @hide */
    @Override
    public void sendBroadcastAsUser(Intent intent, UserHandle user,
            @Nullable String receiverPermission, @Nullable Bundle options) {
        mBase.sendBroadcastAsUser(intent, user, receiverPermission, options);
    }

    /** @hide */
    @Override
    public void sendBroadcastAsUser(Intent intent, UserHandle user,
            @Nullable String receiverPermission, int appOp) {
        mBase.sendBroadcastAsUser(intent, user, receiverPermission, appOp);
    }

    @Override
    public void sendOrderedBroadcastAsUser(Intent intent, UserHandle user,
            @Nullable String receiverPermission, @Nullable BroadcastReceiver resultReceiver,
            @Nullable Handler scheduler, int initialCode, @Nullable String initialData,
            @Nullable Bundle initialExtras) {
        mBase.sendOrderedBroadcastAsUser(intent, user, receiverPermission, resultReceiver,
                scheduler, initialCode, initialData, initialExtras);
    }

    /** @hide */
    @Override
    public void sendOrderedBroadcastAsUser(Intent intent, UserHandle user,
            @Nullable String receiverPermission, int appOp,
            @Nullable BroadcastReceiver resultReceiver, @Nullable Handler scheduler,
            int initialCode, @Nullable String initialData, @Nullable Bundle initialExtras) {
        mBase.sendOrderedBroadcastAsUser(intent, user, receiverPermission, appOp, resultReceiver,
                scheduler, initialCode, initialData, initialExtras);
    }

    /** @hide */
    @Override
    public void sendOrderedBroadcastAsUser(Intent intent, UserHandle user,
            @Nullable String receiverPermission, int appOp, @Nullable Bundle options,
            @Nullable BroadcastReceiver resultReceiver, @Nullable Handler scheduler,
            int initialCode, @Nullable String initialData, @Nullable Bundle initialExtras) {
        mBase.sendOrderedBroadcastAsUser(intent, user, receiverPermission, appOp, options,
                resultReceiver, scheduler, initialCode, initialData, initialExtras);
    }

    @Override
    public void sendOrderedBroadcast(@RequiresPermission @NonNull Intent intent,
            @Nullable String receiverPermission, @Nullable String receiverAppOp,
            @Nullable BroadcastReceiver resultReceiver, @Nullable Handler scheduler,
            int initialCode, @Nullable String initialData, @Nullable Bundle initialExtras) {
        mBase.sendOrderedBroadcast(intent, receiverPermission, receiverAppOp, resultReceiver,
                scheduler, initialCode, initialData, initialExtras);
    }

    @Override
    public void sendOrderedBroadcast(@RequiresPermission @NonNull Intent intent, int initialCode,
            @Nullable String receiverPermission, @Nullable String receiverAppOp,
            @Nullable BroadcastReceiver resultReceiver, @Nullable Handler scheduler,
            @Nullable String initialData, @Nullable Bundle initialExtras,
            @Nullable Bundle options) {
        mBase.sendOrderedBroadcast(intent, initialCode, receiverPermission, receiverAppOp,
                resultReceiver, scheduler, initialData, initialExtras, options);
    }

    @Override
    @Deprecated
    public void sendStickyBroadcast(Intent intent) {
        mBase.sendStickyBroadcast(intent);
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
        mBase.sendStickyBroadcast(intent, options);
    }

    @Override
    @Deprecated
    public void sendStickyOrderedBroadcast(Intent intent,
            @Nullable BroadcastReceiver resultReceiver, @Nullable Handler scheduler,
            int initialCode, @Nullable String initialData, @Nullable Bundle initialExtras) {
        mBase.sendStickyOrderedBroadcast(intent,
                resultReceiver, scheduler, initialCode,
                initialData, initialExtras);
    }

    @Override
    @Deprecated
    public void removeStickyBroadcast(Intent intent) {
        mBase.removeStickyBroadcast(intent);
    }

    @Override
    @Deprecated
    public void sendStickyBroadcastAsUser(Intent intent, UserHandle user) {
        mBase.sendStickyBroadcastAsUser(intent, user);
    }

    /** @hide */
    @Override
    @Deprecated
    public void sendStickyBroadcastAsUser(Intent intent, UserHandle user,
            @Nullable Bundle options) {
        mBase.sendStickyBroadcastAsUser(intent, user, options);
    }

    @Override
    @Deprecated
    public void sendStickyOrderedBroadcastAsUser(Intent intent,
            UserHandle user, @Nullable BroadcastReceiver resultReceiver,
            @Nullable Handler scheduler, int initialCode, @Nullable String initialData,
            @Nullable Bundle initialExtras) {
        mBase.sendStickyOrderedBroadcastAsUser(intent, user, resultReceiver,
                scheduler, initialCode, initialData, initialExtras);
    }

    @Override
    @Deprecated
    public void removeStickyBroadcastAsUser(Intent intent, UserHandle user) {
        mBase.removeStickyBroadcastAsUser(intent, user);
    }

    @Override
    public Intent registerReceiver(@Nullable BroadcastReceiver receiver, IntentFilter filter) {
        return mBase.registerReceiver(receiver, filter);
    }

    @Override
    public Intent registerReceiver(@Nullable BroadcastReceiver receiver, IntentFilter filter,
            int flags) {
        return mBase.registerReceiver(receiver, filter, flags);
    }

    @Override
    public Intent registerReceiver(@Nullable BroadcastReceiver receiver, IntentFilter filter,
            @Nullable String broadcastPermission, @Nullable Handler scheduler) {
        return mBase.registerReceiver(receiver, filter, broadcastPermission,
                scheduler);
    }

    @Override
    public Intent registerReceiver(@Nullable BroadcastReceiver receiver, IntentFilter filter,
            @Nullable String broadcastPermission, @Nullable Handler scheduler, int flags) {
        return mBase.registerReceiver(receiver, filter, broadcastPermission,
                scheduler, flags);
    }

    /** @hide */
    @Override
    @Nullable
    public Intent registerReceiverForAllUsers(@Nullable BroadcastReceiver receiver,
            @NonNull IntentFilter filter, @Nullable String broadcastPermission,
            @Nullable Handler scheduler) {
        return mBase.registerReceiverForAllUsers(receiver, filter, broadcastPermission,
                scheduler);
    }

    /** @hide */
    @Override
    @Nullable
    public Intent registerReceiverForAllUsers(@Nullable BroadcastReceiver receiver,
            @NonNull IntentFilter filter, @Nullable String broadcastPermission,
            @Nullable Handler scheduler, int flags) {
        return mBase.registerReceiverForAllUsers(receiver, filter, broadcastPermission,
                scheduler, flags);
    }

    /** @hide */
    @Override
    @UnsupportedAppUsage
    public Intent registerReceiverAsUser(@Nullable BroadcastReceiver receiver, UserHandle user,
            IntentFilter filter, @Nullable String broadcastPermission,
            @Nullable Handler scheduler) {
        return mBase.registerReceiverAsUser(receiver, user, filter, broadcastPermission,
                scheduler);
    }

    /** @hide */
    @Override
    @UnsupportedAppUsage
    public Intent registerReceiverAsUser(@Nullable BroadcastReceiver receiver, UserHandle user,
            IntentFilter filter, @Nullable String broadcastPermission,
            @Nullable Handler scheduler, int flags) {
        return mBase.registerReceiverAsUser(receiver, user, filter, broadcastPermission,
                scheduler, flags);
    }

    @Override
    public void unregisterReceiver(BroadcastReceiver receiver) {
        mBase.unregisterReceiver(receiver);
    }

    @Override
    public @Nullable ComponentName startService(Intent service) {
        return mBase.startService(service);
    }

    @Override
    public @Nullable ComponentName startForegroundService(Intent service) {
        return mBase.startForegroundService(service);
    }

    @Override
    public boolean stopService(Intent name) {
        return mBase.stopService(name);
    }

    /** @hide */
    @Override
    @UnsupportedAppUsage
    public @Nullable ComponentName startServiceAsUser(Intent service, UserHandle user) {
        return mBase.startServiceAsUser(service, user);
    }

    /** @hide */
    @Override
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public @Nullable ComponentName startForegroundServiceAsUser(Intent service, UserHandle user) {
        return mBase.startForegroundServiceAsUser(service, user);
    }

    /** @hide */
    @Override
    public boolean stopServiceAsUser(Intent name, UserHandle user) {
        return mBase.stopServiceAsUser(name, user);
    }

    @Override
    public boolean bindService(Intent service, ServiceConnection conn,
            int flags) {
        return mBase.bindService(service, conn, flags);
    }

    @Override
    public boolean bindService(@NonNull Intent service, @NonNull ServiceConnection conn,
            @NonNull BindServiceFlags flags) {
        return mBase.bindService(service, conn, flags);
    }

    @Override
    public boolean bindService(Intent service, int flags, Executor executor,
            ServiceConnection conn) {
        return mBase.bindService(service, flags, executor, conn);
    }

    @Override
    public boolean bindService(@NonNull Intent service, @NonNull BindServiceFlags flags,
            @NonNull Executor executor, @NonNull ServiceConnection conn) {
        return mBase.bindService(service, flags, executor, conn);
    }

    @Override
    public boolean bindIsolatedService(Intent service, int flags, String instanceName,
            Executor executor, ServiceConnection conn) {
        return mBase.bindIsolatedService(service, flags, instanceName, executor, conn);
    }

    /** @hide */
    @Override
    public boolean bindServiceAsUser(Intent service, ServiceConnection conn, int flags,
            UserHandle user) {
        return mBase.bindServiceAsUser(service, conn, flags, user);
    }

    /** @hide */
    @Override
    public boolean bindServiceAsUser(Intent service, ServiceConnection conn,
            @NonNull BindServiceFlags flags, UserHandle user) {
        return mBase.bindServiceAsUser(service, conn, flags, user);
    }

    /** @hide */
    @Override
    public boolean bindServiceAsUser(Intent service, ServiceConnection conn, int flags,
            Handler handler, UserHandle user) {
        return mBase.bindServiceAsUser(service, conn, flags, handler, user);
    }

    /** @hide */
    @Override
    public boolean bindServiceAsUser(Intent service, ServiceConnection conn,
            @NonNull BindServiceFlags flags, Handler handler, UserHandle user) {
        return mBase.bindServiceAsUser(service, conn, flags, handler, user);
    }

    @Override
    public void updateServiceGroup(ServiceConnection conn, int group, int importance) {
        mBase.updateServiceGroup(conn, group, importance);
    }

    @Override
    public void unbindService(ServiceConnection conn) {
        mBase.unbindService(conn);
    }

    @Override
    public boolean startInstrumentation(ComponentName className,
            @Nullable String profileFile, @Nullable Bundle arguments) {
        return mBase.startInstrumentation(className, profileFile, arguments);
    }

    @Override
    public @Nullable Object getSystemService(String name) {
        return mBase.getSystemService(name);
    }

    @Override
    public String getSystemServiceName(Class<?> serviceClass) {
        return mBase.getSystemServiceName(serviceClass);
    }

    @Override
    public int checkPermission(String permission, int pid, int uid) {
        return mBase.checkPermission(permission, pid, uid);
    }

    /** @hide */
    @Override
    public int checkPermission(String permission, int pid, int uid, IBinder callerToken) {
        return mBase.checkPermission(permission, pid, uid, callerToken);
    }

    @Override
    public int checkCallingPermission(String permission) {
        return mBase.checkCallingPermission(permission);
    }

    @Override
    public int checkCallingOrSelfPermission(String permission) {
        return mBase.checkCallingOrSelfPermission(permission);
    }

    @Override
    public int checkSelfPermission(String permission) {
       return mBase.checkSelfPermission(permission);
    }

    @Override
    public void enforcePermission(
            String permission, int pid, int uid, @Nullable String message) {
        mBase.enforcePermission(permission, pid, uid, message);
    }

    @Override
    public void enforceCallingPermission(String permission, @Nullable String message) {
        mBase.enforceCallingPermission(permission, message);
    }

    @Override
    public void enforceCallingOrSelfPermission(
            String permission, @Nullable String message) {
        mBase.enforceCallingOrSelfPermission(permission, message);
    }

    @Override
    public void grantUriPermission(String toPackage, Uri uri, int modeFlags) {
        mBase.grantUriPermission(toPackage, uri, modeFlags);
    }

    @Override
    public void revokeUriPermission(Uri uri, int modeFlags) {
        mBase.revokeUriPermission(uri, modeFlags);
    }

    @Override
    public void revokeUriPermission(String targetPackage, Uri uri, int modeFlags) {
        mBase.revokeUriPermission(targetPackage, uri, modeFlags);
    }

    @Override
    public int checkUriPermission(Uri uri, int pid, int uid, int modeFlags) {
        return mBase.checkUriPermission(uri, pid, uid, modeFlags);
    }

    @FlaggedApi(android.security.Flags.FLAG_CONTENT_URI_PERMISSION_APIS)
    @Override
    public int checkContentUriPermissionFull(@NonNull Uri uri, int pid, int uid, int modeFlags) {
        return mBase.checkContentUriPermissionFull(uri, pid, uid, modeFlags);
    }

    @NonNull
    @Override
    public int[] checkUriPermissions(@NonNull List<Uri> uris, int pid, int uid,
            int modeFlags) {
        return mBase.checkUriPermissions(uris, pid, uid, modeFlags);
    }

    /** @hide */
    @Override
    public int checkUriPermission(Uri uri, int pid, int uid, int modeFlags, IBinder callerToken) {
        return mBase.checkUriPermission(uri, pid, uid, modeFlags, callerToken);
    }

    @Override
    public int checkCallingUriPermission(Uri uri, int modeFlags) {
        return mBase.checkCallingUriPermission(uri, modeFlags);
    }

    @NonNull
    @Override
    public int[] checkCallingUriPermissions(@NonNull List<Uri> uris, int modeFlags) {
        return mBase.checkCallingUriPermissions(uris, modeFlags);
    }

    @Override
    public int checkCallingOrSelfUriPermission(Uri uri, int modeFlags) {
        return mBase.checkCallingOrSelfUriPermission(uri, modeFlags);
    }

    @NonNull
    @Override
    public int[] checkCallingOrSelfUriPermissions(@NonNull List<Uri> uris, int modeFlags) {
        return mBase.checkCallingOrSelfUriPermissions(uris, modeFlags);
    }

    @Override
    public int checkUriPermission(@Nullable Uri uri, @Nullable String readPermission,
            @Nullable String writePermission, int pid, int uid, int modeFlags) {
        return mBase.checkUriPermission(uri, readPermission, writePermission,
                pid, uid, modeFlags);
    }

    @Override
    public void enforceUriPermission(
            Uri uri, int pid, int uid, int modeFlags, String message) {
        mBase.enforceUriPermission(uri, pid, uid, modeFlags, message);
    }

    @Override
    public void enforceCallingUriPermission(
            Uri uri, int modeFlags, String message) {
        mBase.enforceCallingUriPermission(uri, modeFlags, message);
    }

    @Override
    public void enforceCallingOrSelfUriPermission(
            Uri uri, int modeFlags, String message) {
        mBase.enforceCallingOrSelfUriPermission(uri, modeFlags, message);
    }

    @Override
    public void enforceUriPermission(
            @Nullable Uri uri, @Nullable String readPermission, @Nullable String writePermission,
            int pid, int uid, int modeFlags, @Nullable String message) {
        mBase.enforceUriPermission(
                uri, readPermission, writePermission, pid, uid, modeFlags,
                message);
    }

    @Override
    public void revokeSelfPermissionsOnKill(@NonNull Collection<String> permissions) {
        mBase.revokeSelfPermissionsOnKill(permissions);
    }

    @Override
    public Context createPackageContext(String packageName, int flags)
        throws PackageManager.NameNotFoundException {
        return mBase.createPackageContext(packageName, flags);
    }

    /** @hide */
    @Override
    public Context createPackageContextAsUser(String packageName, int flags, UserHandle user)
            throws PackageManager.NameNotFoundException {
        return mBase.createPackageContextAsUser(packageName, flags, user);
    }

    /** @hide */
    @Override
    public Context createContextAsUser(UserHandle user, @CreatePackageOptions int flags) {
        return mBase.createContextAsUser(user, flags);
    }

    /** @hide */
    @Override
    @UnsupportedAppUsage(trackingBug = 175981568)
    public Context createApplicationContext(ApplicationInfo application,
            int flags) throws PackageManager.NameNotFoundException {
        return mBase.createApplicationContext(application, flags);
    }

    /** @hide */
    @Override
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @NonNull
    public Context createContextForSdkInSandbox(@NonNull ApplicationInfo sdkInfo, int flags)
            throws PackageManager.NameNotFoundException {
        return mBase.createContextForSdkInSandbox(sdkInfo, flags);
    }

    /** @hide */
    @Override
    public Context createContextForSplit(String splitName)
            throws PackageManager.NameNotFoundException {
        return mBase.createContextForSplit(splitName);
    }

    /** @hide */
    @Override
    public int getUserId() {
        return mBase.getUserId();
    }

    /** @hide */
    @Override
    public UserHandle getUser() {
        return mBase.getUser();
    }

    @Override
    public Context createConfigurationContext(Configuration overrideConfiguration) {
        return mBase.createConfigurationContext(overrideConfiguration);
    }

    @Override
    public Context createDisplayContext(Display display) {
        return mBase.createDisplayContext(display);
    }

    @Override
    public @NonNull Context createDeviceContext(int deviceId) {
        return mBase.createDeviceContext(deviceId);
    }

    @Override
    @NonNull
    public Context createWindowContext(@WindowType int type, @Nullable Bundle options) {
        return mBase.createWindowContext(type, options);
    }

    @Override
    @NonNull
    public Context createWindowContext(@NonNull Display display, @WindowType int type,
            @Nullable Bundle options) {
        return mBase.createWindowContext(display, type, options);
    }

    @Override
    @NonNull
    public Context createContext(@NonNull ContextParams contextParams) {
        return mBase.createContext(contextParams);
    }

    @Override
    public @NonNull Context createAttributionContext(@Nullable String attributionTag) {
        return mBase.createAttributionContext(attributionTag);
    }

    @NonNull
    @Override
    public AttributionSource getAttributionSource() {
        return mBase.getAttributionSource();
    }

    @Override
    public boolean isRestricted() {
        return mBase.isRestricted();
    }

    /** @hide */
    @Override
    public DisplayAdjustments getDisplayAdjustments(int displayId) {
        return mBase.getDisplayAdjustments(displayId);
    }

    @Override
    public @Nullable Display getDisplay() {
        return mBase.getDisplay();
    }

    /** @hide */
    @Override
    public @Nullable Display getDisplayNoVerify() {
        return mBase.getDisplayNoVerify();
    }

    /**
     * @hide
     */
    @Override
    public int getDisplayId() {
        return mBase.getDisplayId();
    }

    /**
     * @hide
     */
    @Override
    public int getAssociatedDisplayId() {
        return mBase.getAssociatedDisplayId();
    }

    /**
     * @hide
     */
    @Override
    public void updateDisplay(int displayId) {
        mBase.updateDisplay(displayId);
    }

    /**
     * @hide
     */
    @Override
    public void updateDeviceId(int deviceId) {
        mBase.updateDeviceId(deviceId);
    }

    @Override
    public int getDeviceId() {
        return mBase.getDeviceId();
    }

    @Override
    public void registerDeviceIdChangeListener(@NonNull @CallbackExecutor Executor executor,
            @NonNull IntConsumer listener) {
        mBase.registerDeviceIdChangeListener(executor, listener);
    }

    @Override
    public void unregisterDeviceIdChangeListener(@NonNull IntConsumer listener) {
        mBase.unregisterDeviceIdChangeListener(listener);
    }

    @Override
    public Context createDeviceProtectedStorageContext() {
        return mBase.createDeviceProtectedStorageContext();
    }

    /** {@hide} */
    @SystemApi
    @Override
    public Context createCredentialProtectedStorageContext() {
        return mBase.createCredentialProtectedStorageContext();
    }

    /** @hide */
    @UiContext
    @NonNull
    @Override
    public Context createTokenContext(@NonNull IBinder token, @NonNull Display display) {
        return mBase.createTokenContext(token, display);
    }

    @Override
    public boolean isDeviceProtectedStorage() {
        return mBase.isDeviceProtectedStorage();
    }

    /** {@hide} */
    @SystemApi
    @Override
    public boolean isCredentialProtectedStorage() {
        return mBase.isCredentialProtectedStorage();
    }

    /** {@hide} */
    @Override
    public boolean canLoadUnsafeResources() {
        return mBase.canLoadUnsafeResources();
    }

    /**
     * @hide
     */
    @Override
    public @Nullable IBinder getActivityToken() {
        return mBase.getActivityToken();
    }

    /**
     * @hide
     */
    @Override
    public @Nullable IBinder getWindowContextToken() {
        return mBase != null ? mBase.getWindowContextToken() : null;
    }

    /**
     * @hide
     */
    @Override
    public @Nullable IServiceConnection getServiceDispatcher(ServiceConnection conn,
            Handler handler, long flags) {
        return mBase.getServiceDispatcher(conn, handler, flags);
    }

    /**
     * @hide
     */
    @Override
    public IApplicationThread getIApplicationThread() {
        return mBase.getIApplicationThread();
    }

    /**
     * @hide
     */
    @Override
    public IBinder getProcessToken() {
        return mBase.getProcessToken();
    }

    /**
     * @hide
     */
    @Override
    public Handler getMainThreadHandler() {
        return mBase.getMainThreadHandler();
    }

    /**
     * @hide
     */
    @Override
    public int getNextAutofillId() {
        return mBase.getNextAutofillId();
    }

    /**
     * @hide
     */
    @Override
    public AutofillClient getAutofillClient() {
        return mBase.getAutofillClient();
    }

    /**
     * @hide
     */
    @Override
    public void setAutofillClient(AutofillClient client) {
        mBase.setAutofillClient(client);
    }

    /** @hide */
    @Override
    public AutofillOptions getAutofillOptions() {
        return mBase == null ? null : mBase.getAutofillOptions();
    }

    /** @hide */
    @Override
    public void setAutofillOptions(AutofillOptions options) {
        if (mBase != null) {
            mBase.setAutofillOptions(options);
        }
    }

    /**
     * @hide
     */
    @Override
    public @Nullable ContentCaptureOptions getContentCaptureOptions() {
        return mBase == null ? null : mBase.getContentCaptureOptions();
    }

    /**
     * @hide
     */
    @TestApi
    @Override
    public void setContentCaptureOptions(@Nullable ContentCaptureOptions options) {
        if (mBase != null) {
            mBase.setContentCaptureOptions(options);
        }
    }

    /**
     * @hide
     */
    @Override
    public boolean isUiContext() {
        if (mBase == null) {
            return false;
        }
        return mBase.isUiContext();
    }

    /**
     * @hide
     */
    @Override
    public boolean isConfigurationContext() {
        if (mBase == null) {
            return false;
        }
        return mBase.isConfigurationContext();
    }

    /**
     * Add a new {@link ComponentCallbacks} to the base application of the
     * Context, which will be called at the same times as the ComponentCallbacks
     * methods of activities and other components are called. Note that you
     * <em>must</em> be sure to use {@link #unregisterComponentCallbacks} when
     * appropriate in the future; this will not be removed for you.
     * <p>
     * After {@link Build.VERSION_CODES#TIRAMISU}, the {@link ComponentCallbacks} will be registered
     * to {@link #getBaseContext() the base Context}, and can be only used after
     * {@link #attachBaseContext(Context)}. Users can still call to
     * {@code getApplicationContext().registerComponentCallbacks(ComponentCallbacks)} to add
     * {@link ComponentCallbacks} to the base application.
     *
     * @param callback The interface to call.  This can be either a
     * {@link ComponentCallbacks} or {@link ComponentCallbacks2} interface.
     * @throws IllegalStateException if this method calls before {@link #attachBaseContext(Context)}
     */
    @Override
    @android.ravenwood.annotation.RavenwoodThrow
    public void registerComponentCallbacks(ComponentCallbacks callback) {
        if (mBase != null) {
            mBase.registerComponentCallbacks(callback);
        } else if (!CompatChanges.isChangeEnabled(OVERRIDABLE_COMPONENT_CALLBACKS)) {
            super.registerComponentCallbacks(callback);
            synchronized (mLock) {
                // Also register ComponentCallbacks to ContextWrapper, so we can find the correct
                // Context to unregister it for compatibility.
                if (mCallbacksRegisteredToSuper == null) {
                    mCallbacksRegisteredToSuper = new ArrayList<>();
                }
                mCallbacksRegisteredToSuper.add(callback);
            }
        } else {
            // Throw exception for Application targeting T+
            throw new IllegalStateException("ComponentCallbacks must be registered after "
                    + "this ContextWrapper is attached to a base Context.");
        }
    }

    /**
     * Remove a {@link ComponentCallbacks} object that was previously registered
     * with {@link #registerComponentCallbacks(ComponentCallbacks)}.
     * <p>
     * After {@link Build.VERSION_CODES#TIRAMISU}, the {@link ComponentCallbacks} will be
     * unregistered to {@link #getBaseContext() the base Context}, and can be only used after
     * {@link #attachBaseContext(Context)}
     * </p>
     *
     * @param callback The interface to call.  This can be either a
     * {@link ComponentCallbacks} or {@link ComponentCallbacks2} interface.
     * @throws IllegalStateException if this method calls before {@link #attachBaseContext(Context)}
     */
    @Override
    @android.ravenwood.annotation.RavenwoodThrow
    public void unregisterComponentCallbacks(ComponentCallbacks callback) {
        // It usually means the ComponentCallbacks is registered before this ContextWrapper attaches
        // to a base Context and Application is targeting prior to S-v2. We should unregister the
        // ComponentCallbacks to the Application Context instead to prevent leak.
        synchronized (mLock) {
            if (mCallbacksRegisteredToSuper != null
                    && mCallbacksRegisteredToSuper.contains(callback)) {
                super.unregisterComponentCallbacks(callback);
                mCallbacksRegisteredToSuper.remove(callback);
            } else if (mBase != null) {
                mBase.unregisterComponentCallbacks(callback);
            } else if (CompatChanges.isChangeEnabled(OVERRIDABLE_COMPONENT_CALLBACKS)) {
                // Throw exception for Application that is targeting S-v2+
                throw new IllegalStateException("ComponentCallbacks must be unregistered after "
                        + "this ContextWrapper is attached to a base Context.");
            }
        }
        // Do nothing if the callback hasn't been registered to Application Context by
        // super.unregisterComponentCallbacks() for Application that is targeting prior to T.
    }

    /**
     * Closes temporary system dialogs. Some examples of temporary system dialogs are the
     * notification window-shade and the recent tasks dialog.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.BROADCAST_CLOSE_SYSTEM_DIALOGS)
    public void closeSystemDialogs() {
        mBase.closeSystemDialogs();
    }
}
