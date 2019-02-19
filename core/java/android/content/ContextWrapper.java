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

import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.annotation.UnsupportedAppUsage;
import android.app.IApplicationThread;
import android.app.IServiceConnection;
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
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.UserHandle;
import android.view.Display;
import android.view.DisplayAdjustments;
import android.view.autofill.AutofillManager.AutofillClient;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.Executor;

/**
 * Proxying implementation of Context that simply delegates all of its calls to
 * another Context.  Can be subclassed to modify behavior without changing
 * the original Context.
 */
public class ContextWrapper extends Context {
    @UnsupportedAppUsage
    Context mBase;

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

    @Override
    public File getNoBackupFilesDir() {
        return mBase.getNoBackupFilesDir();
    }

    @Override
    public File getExternalFilesDir(String type) {
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
    public File getExternalCacheDir() {
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
    public File getPreloadsFileCache() {
        return mBase.getPreloadsFileCache();
    }

    @Override
    public SQLiteDatabase openOrCreateDatabase(String name, int mode, CursorFactory factory) {
        return mBase.openOrCreateDatabase(name, mode, factory);
    }

    @Override
    public SQLiteDatabase openOrCreateDatabase(String name, int mode, CursorFactory factory,
            DatabaseErrorHandler errorHandler) {
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
            String who, Intent intent, int requestCode, Bundle options) {
        mBase.startActivityForResult(who, intent, requestCode, options);
    }

    /** @hide **/
    public boolean canStartActivityForResult() {
        return mBase.canStartActivityForResult();
    }

    @Override
    public void startActivity(Intent intent, Bundle options) {
        mBase.startActivity(intent, options);
    }

    /** @hide */
    @Override
    public void startActivityAsUser(Intent intent, Bundle options, UserHandle user) {
        mBase.startActivityAsUser(intent, options, user);
    }

    @Override
    public void startActivities(Intent[] intents) {
        mBase.startActivities(intents);
    }

    @Override
    public void startActivities(Intent[] intents, Bundle options) {
        mBase.startActivities(intents, options);
    }

    /** @hide */
    @Override
    public int startActivitiesAsUser(Intent[] intents, Bundle options, UserHandle userHandle) {
        return mBase.startActivitiesAsUser(intents, options, userHandle);
    }

    @Override
    public void startIntentSender(IntentSender intent,
            Intent fillInIntent, int flagsMask, int flagsValues, int extraFlags)
            throws IntentSender.SendIntentException {
        mBase.startIntentSender(intent, fillInIntent, flagsMask,
                flagsValues, extraFlags);
    }

    @Override
    public void startIntentSender(IntentSender intent,
            Intent fillInIntent, int flagsMask, int flagsValues, int extraFlags,
            Bundle options) throws IntentSender.SendIntentException {
        mBase.startIntentSender(intent, fillInIntent, flagsMask,
                flagsValues, extraFlags, options);
    }
    
    @Override
    public void sendBroadcast(Intent intent) {
        mBase.sendBroadcast(intent);
    }

    @Override
    public void sendBroadcast(Intent intent, String receiverPermission) {
        mBase.sendBroadcast(intent, receiverPermission);
    }

    /** @hide */
    @Override
    public void sendBroadcastMultiplePermissions(Intent intent, String[] receiverPermissions) {
        mBase.sendBroadcastMultiplePermissions(intent, receiverPermissions);
    }

    /** @hide */
    @Override
    public void sendBroadcastAsUserMultiplePermissions(Intent intent, UserHandle user,
            String[] receiverPermissions) {
        mBase.sendBroadcastAsUserMultiplePermissions(intent, user, receiverPermissions);
    }

    /** @hide */
    @SystemApi
    @Override
    public void sendBroadcast(Intent intent, String receiverPermission, Bundle options) {
        mBase.sendBroadcast(intent, receiverPermission, options);
    }

    /** @hide */
    @Override
    public void sendBroadcast(Intent intent, String receiverPermission, int appOp) {
        mBase.sendBroadcast(intent, receiverPermission, appOp);
    }

    @Override
    public void sendOrderedBroadcast(Intent intent,
            String receiverPermission) {
        mBase.sendOrderedBroadcast(intent, receiverPermission);
    }

    @Override
    public void sendOrderedBroadcast(
        Intent intent, String receiverPermission, BroadcastReceiver resultReceiver,
        Handler scheduler, int initialCode, String initialData,
        Bundle initialExtras) {
        mBase.sendOrderedBroadcast(intent, receiverPermission,
                resultReceiver, scheduler, initialCode,
                initialData, initialExtras);
    }

    /** @hide */
    @SystemApi
    @Override
    public void sendOrderedBroadcast(
            Intent intent, String receiverPermission, Bundle options, BroadcastReceiver resultReceiver,
            Handler scheduler, int initialCode, String initialData,
            Bundle initialExtras) {
        mBase.sendOrderedBroadcast(intent, receiverPermission,
                options, resultReceiver, scheduler, initialCode,
                initialData, initialExtras);
    }

    /** @hide */
    @Override
    public void sendOrderedBroadcast(
        Intent intent, String receiverPermission, int appOp, BroadcastReceiver resultReceiver,
        Handler scheduler, int initialCode, String initialData,
        Bundle initialExtras) {
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
            String receiverPermission, Bundle options) {
        mBase.sendBroadcastAsUser(intent, user, receiverPermission, options);
    }

    /** @hide */
    @Override
    public void sendBroadcastAsUser(Intent intent, UserHandle user,
            String receiverPermission, int appOp) {
        mBase.sendBroadcastAsUser(intent, user, receiverPermission, appOp);
    }

    @Override
    public void sendOrderedBroadcastAsUser(Intent intent, UserHandle user,
            String receiverPermission, BroadcastReceiver resultReceiver, Handler scheduler,
            int initialCode, String initialData, Bundle initialExtras) {
        mBase.sendOrderedBroadcastAsUser(intent, user, receiverPermission, resultReceiver,
                scheduler, initialCode, initialData, initialExtras);
    }

    /** @hide */
    @Override
    public void sendOrderedBroadcastAsUser(Intent intent, UserHandle user,
            String receiverPermission, int appOp, BroadcastReceiver resultReceiver,
            Handler scheduler, int initialCode, String initialData, Bundle initialExtras) {
        mBase.sendOrderedBroadcastAsUser(intent, user, receiverPermission, appOp, resultReceiver,
                scheduler, initialCode, initialData, initialExtras);
    }

    /** @hide */
    @Override
    public void sendOrderedBroadcastAsUser(Intent intent, UserHandle user,
            String receiverPermission, int appOp, Bundle options, BroadcastReceiver resultReceiver,
            Handler scheduler, int initialCode, String initialData, Bundle initialExtras) {
        mBase.sendOrderedBroadcastAsUser(intent, user, receiverPermission, appOp, options,
                resultReceiver, scheduler, initialCode, initialData, initialExtras);
    }

    @Override
    @Deprecated
    public void sendStickyBroadcast(Intent intent) {
        mBase.sendStickyBroadcast(intent);
    }

    @Override
    @Deprecated
    public void sendStickyOrderedBroadcast(
        Intent intent, BroadcastReceiver resultReceiver,
        Handler scheduler, int initialCode, String initialData,
        Bundle initialExtras) {
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
    public void sendStickyBroadcastAsUser(Intent intent, UserHandle user, Bundle options) {
        mBase.sendStickyBroadcastAsUser(intent, user, options);
    }

    @Override
    @Deprecated
    public void sendStickyOrderedBroadcastAsUser(Intent intent,
            UserHandle user, BroadcastReceiver resultReceiver,
            Handler scheduler, int initialCode, String initialData,
            Bundle initialExtras) {
        mBase.sendStickyOrderedBroadcastAsUser(intent, user, resultReceiver,
                scheduler, initialCode, initialData, initialExtras);
    }

    @Override
    @Deprecated
    public void removeStickyBroadcastAsUser(Intent intent, UserHandle user) {
        mBase.removeStickyBroadcastAsUser(intent, user);
    }

    @Override
    public Intent registerReceiver(
        BroadcastReceiver receiver, IntentFilter filter) {
        return mBase.registerReceiver(receiver, filter);
    }

    @Override
    public Intent registerReceiver(
        BroadcastReceiver receiver, IntentFilter filter, int flags) {
        return mBase.registerReceiver(receiver, filter, flags);
    }

    @Override
    public Intent registerReceiver(
        BroadcastReceiver receiver, IntentFilter filter,
        String broadcastPermission, Handler scheduler) {
        return mBase.registerReceiver(receiver, filter, broadcastPermission,
                scheduler);
    }

    @Override
    public Intent registerReceiver(
        BroadcastReceiver receiver, IntentFilter filter,
        String broadcastPermission, Handler scheduler, int flags) {
        return mBase.registerReceiver(receiver, filter, broadcastPermission,
                scheduler, flags);
    }

    /** @hide */
    @Override
    @UnsupportedAppUsage
    public Intent registerReceiverAsUser(
        BroadcastReceiver receiver, UserHandle user, IntentFilter filter,
        String broadcastPermission, Handler scheduler) {
        return mBase.registerReceiverAsUser(receiver, user, filter, broadcastPermission,
                scheduler);
    }

    @Override
    public void unregisterReceiver(BroadcastReceiver receiver) {
        mBase.unregisterReceiver(receiver);
    }

    @Override
    public ComponentName startService(Intent service) {
        return mBase.startService(service);
    }

    @Override
    public ComponentName startForegroundService(Intent service) {
        return mBase.startForegroundService(service);
    }

    @Override
    public boolean stopService(Intent name) {
        return mBase.stopService(name);
    }

    /** @hide */
    @Override
    @UnsupportedAppUsage
    public ComponentName startServiceAsUser(Intent service, UserHandle user) {
        return mBase.startServiceAsUser(service, user);
    }

    /** @hide */
    @Override
    @UnsupportedAppUsage
    public ComponentName startForegroundServiceAsUser(Intent service, UserHandle user) {
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
    public boolean bindIsolatedService(Intent service, ServiceConnection conn,
            int flags, String instanceName) {
        return mBase.bindIsolatedService(service, conn, flags, instanceName);
    }

    /** @hide */
    @Override
    public boolean bindServiceAsUser(Intent service, ServiceConnection conn, int flags,
            UserHandle user) {
        return mBase.bindServiceAsUser(service, conn, flags, user);
    }

    /** @hide */
    @Override
    public boolean bindServiceAsUser(Intent service, ServiceConnection conn, int flags,
            Handler handler, UserHandle user) {
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
            String profileFile, Bundle arguments) {
        return mBase.startInstrumentation(className, profileFile, arguments);
    }

    @Override
    public Object getSystemService(String name) {
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
            String permission, int pid, int uid, String message) {
        mBase.enforcePermission(permission, pid, uid, message);
    }

    @Override
    public void enforceCallingPermission(String permission, String message) {
        mBase.enforceCallingPermission(permission, message);
    }

    @Override
    public void enforceCallingOrSelfPermission(
            String permission, String message) {
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

    /** @hide */
    @Override
    public int checkUriPermission(Uri uri, int pid, int uid, int modeFlags, IBinder callerToken) {
        return mBase.checkUriPermission(uri, pid, uid, modeFlags, callerToken);
    }

    @Override
    public int checkCallingUriPermission(Uri uri, int modeFlags) {
        return mBase.checkCallingUriPermission(uri, modeFlags);
    }

    @Override
    public int checkCallingOrSelfUriPermission(Uri uri, int modeFlags) {
        return mBase.checkCallingOrSelfUriPermission(uri, modeFlags);
    }

    @Override
    public int checkUriPermission(Uri uri, String readPermission,
            String writePermission, int pid, int uid, int modeFlags) {
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
            Uri uri, String readPermission, String writePermission,
            int pid, int uid, int modeFlags, String message) {
        mBase.enforceUriPermission(
                uri, readPermission, writePermission, pid, uid, modeFlags,
                message);
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
    @UnsupportedAppUsage
    public Context createApplicationContext(ApplicationInfo application,
            int flags) throws PackageManager.NameNotFoundException {
        return mBase.createApplicationContext(application, flags);
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

    @Override
    public Context createConfigurationContext(Configuration overrideConfiguration) {
        return mBase.createConfigurationContext(overrideConfiguration);
    }

    @Override
    public Context createDisplayContext(Display display) {
        return mBase.createDisplayContext(display);
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

    /**
     * @hide
     */
    @Override
    @UnsupportedAppUsage
    public Display getDisplay() {
        return mBase.getDisplay();
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
    public void updateDisplay(int displayId) {
        mBase.updateDisplay(displayId);
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
    public IBinder getActivityToken() {
        return mBase.getActivityToken();
    }

    /**
     * @hide
     */
    @Override
    public IServiceConnection getServiceDispatcher(ServiceConnection conn, Handler handler,
            int flags) {
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

    /**
     * @hide
     */
    @Override
    public boolean isAutofillCompatibilityEnabled() {
        return mBase != null && mBase.isAutofillCompatibilityEnabled();
    }

    /**
     * @hide
     */
    @TestApi
    @Override
    public void setAutofillCompatibilityEnabled(boolean autofillCompatEnabled) {
        if (mBase != null) {
            mBase.setAutofillCompatibilityEnabled(autofillCompatEnabled);
        }
    }

    /**
     * @hide
     */
    @Override
    public ContentCaptureOptions getContentCaptureOptions() {
        return mBase == null ? null : mBase.getContentCaptureOptions();
    }

    /**
     * @hide
     */
    @TestApi
    @Override
    public void setContentCaptureOptions(ContentCaptureOptions options) {
        if (mBase != null) {
            mBase.setContentCaptureOptions(options);
        }
    }
}
