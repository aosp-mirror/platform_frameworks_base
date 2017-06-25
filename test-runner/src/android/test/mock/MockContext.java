/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.test.mock;

import android.annotation.SystemApi;
import android.app.IApplicationThread;
import android.app.IServiceConnection;
import android.app.Notification;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.content.IntentSender;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.UserHandle;
import android.view.DisplayAdjustments;
import android.view.Display;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * A mock {@link android.content.Context} class.  All methods are non-functional and throw
 * {@link java.lang.UnsupportedOperationException}.  You can use this to inject other dependencies,
 * mocks, or monitors into the classes you are testing.
 */
public class MockContext extends Context {

    @Override
    public AssetManager getAssets() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Resources getResources() {
        throw new UnsupportedOperationException();
    }

    @Override
    public PackageManager getPackageManager() {
        throw new UnsupportedOperationException();
    }

    @Override
    public ContentResolver getContentResolver() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Looper getMainLooper() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Context getApplicationContext() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setTheme(int resid) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Resources.Theme getTheme() {
        throw new UnsupportedOperationException();
    }

    @Override
    public ClassLoader getClassLoader() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getPackageName() {
        throw new UnsupportedOperationException();
    }

    /** @hide */
    @Override
    public String getBasePackageName() {
        throw new UnsupportedOperationException();
    }

    /** @hide */
    @Override
    public String getOpPackageName() {
        throw new UnsupportedOperationException();
    }

    @Override
    public ApplicationInfo getApplicationInfo() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getPackageResourcePath() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getPackageCodePath() {
        throw new UnsupportedOperationException();
    }

    @Override
    public SharedPreferences getSharedPreferences(String name, int mode) {
        throw new UnsupportedOperationException();
    }

    /** @removed */
    @Override
    public SharedPreferences getSharedPreferences(File file, int mode) {
        throw new UnsupportedOperationException();
    }

    /** @hide */
    @Override
    public void reloadSharedPreferences() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean moveSharedPreferencesFrom(Context sourceContext, String name) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean deleteSharedPreferences(String name) {
        throw new UnsupportedOperationException();
    }

    @Override
    public FileInputStream openFileInput(String name) throws FileNotFoundException {
        throw new UnsupportedOperationException();
    }

    @Override
    public FileOutputStream openFileOutput(String name, int mode) throws FileNotFoundException {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean deleteFile(String name) {
        throw new UnsupportedOperationException();
    }

    @Override
    public File getFileStreamPath(String name) {
        throw new UnsupportedOperationException();
    }

    /** @removed */
    @Override
    public File getSharedPreferencesPath(String name) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String[] fileList() {
        throw new UnsupportedOperationException();
    }

    @Override
    public File getDataDir() {
        throw new UnsupportedOperationException();
    }

    @Override
    public File getFilesDir() {
        throw new UnsupportedOperationException();
    }

    @Override
    public File getNoBackupFilesDir() {
        throw new UnsupportedOperationException();
    }

    @Override
    public File getExternalFilesDir(String type) {
        throw new UnsupportedOperationException();
    }

    @Override
    public File getObbDir() {
        throw new UnsupportedOperationException();
    }

    @Override
    public File getCacheDir() {
        throw new UnsupportedOperationException();
    }

    @Override
    public File getCodeCacheDir() {
        throw new UnsupportedOperationException();
    }

    @Override
    public File getExternalCacheDir() {
        throw new UnsupportedOperationException();
    }

    @Override
    public File getDir(String name, int mode) {
        throw new UnsupportedOperationException();
    }

    @Override
    public SQLiteDatabase openOrCreateDatabase(String file, int mode,
            SQLiteDatabase.CursorFactory factory) {
        throw new UnsupportedOperationException();
    }

    @Override
    public SQLiteDatabase openOrCreateDatabase(String file, int mode,
            SQLiteDatabase.CursorFactory factory, DatabaseErrorHandler errorHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public File getDatabasePath(String name) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String[] databaseList() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean moveDatabaseFrom(Context sourceContext, String name) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean deleteDatabase(String name) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Drawable getWallpaper() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Drawable peekWallpaper() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getWallpaperDesiredMinimumWidth() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getWallpaperDesiredMinimumHeight() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setWallpaper(Bitmap bitmap) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setWallpaper(InputStream data) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void clearWallpaper() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void startActivity(Intent intent) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void startActivity(Intent intent, Bundle options) {
        startActivity(intent);
    }

    @Override
    public void startActivities(Intent[] intents) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void startActivities(Intent[] intents, Bundle options) {
        startActivities(intents);
    }

    @Override
    public void startIntentSender(IntentSender intent,
            Intent fillInIntent, int flagsMask, int flagsValues, int extraFlags)
            throws IntentSender.SendIntentException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void startIntentSender(IntentSender intent,
            Intent fillInIntent, int flagsMask, int flagsValues, int extraFlags,
            Bundle options) throws IntentSender.SendIntentException {
        startIntentSender(intent, fillInIntent, flagsMask, flagsValues, extraFlags);
    }

    @Override
    public void sendBroadcast(Intent intent) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void sendBroadcast(Intent intent, String receiverPermission) {
        throw new UnsupportedOperationException();
    }

    /** @hide */
    @Override
    public void sendBroadcastMultiplePermissions(Intent intent, String[] receiverPermissions) {
        throw new UnsupportedOperationException();
    }

    /** @hide */
    @SystemApi
    @Override
    public void sendBroadcast(Intent intent, String receiverPermission, Bundle options) {
        throw new UnsupportedOperationException();
    }

    /** @hide */
    @Override
    public void sendBroadcast(Intent intent, String receiverPermission, int appOp) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void sendOrderedBroadcast(Intent intent,
            String receiverPermission) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void sendOrderedBroadcast(Intent intent, String receiverPermission,
            BroadcastReceiver resultReceiver, Handler scheduler, int initialCode, String initialData,
           Bundle initialExtras) {
        throw new UnsupportedOperationException();
    }

    /** @hide */
    @SystemApi
    @Override
    public void sendOrderedBroadcast(Intent intent, String receiverPermission,
            Bundle options, BroadcastReceiver resultReceiver, Handler scheduler, int initialCode, String initialData,
            Bundle initialExtras) {
        throw new UnsupportedOperationException();
    }

    /** @hide */
    @Override
    public void sendOrderedBroadcast(Intent intent, String receiverPermission, int appOp,
            BroadcastReceiver resultReceiver, Handler scheduler, int initialCode, String initialData,
           Bundle initialExtras) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void sendBroadcastAsUser(Intent intent, UserHandle user) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void sendBroadcastAsUser(Intent intent, UserHandle user,
            String receiverPermission) {
        throw new UnsupportedOperationException();
    }

    /** @hide */
    @SystemApi
    @Override
    public void sendBroadcastAsUser(Intent intent, UserHandle user,
            String receiverPermission, Bundle options) {
        throw new UnsupportedOperationException();
    }

    /** @hide */
    @Override
    public void sendBroadcastAsUser(Intent intent, UserHandle user,
            String receiverPermission, int appOp) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void sendOrderedBroadcastAsUser(Intent intent, UserHandle user,
            String receiverPermission, BroadcastReceiver resultReceiver, Handler scheduler,
            int initialCode, String initialData, Bundle initialExtras) {
        throw new UnsupportedOperationException();
    }

    /** @hide */
    @Override
    public void sendOrderedBroadcastAsUser(Intent intent, UserHandle user,
            String receiverPermission, int appOp, BroadcastReceiver resultReceiver,
            Handler scheduler, int initialCode, String initialData, Bundle initialExtras) {
        throw new UnsupportedOperationException();
    }

    /** @hide */
    @Override
    public void sendOrderedBroadcastAsUser(Intent intent, UserHandle user,
            String receiverPermission, int appOp, Bundle options, BroadcastReceiver resultReceiver,
            Handler scheduler, int initialCode, String initialData, Bundle initialExtras) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void sendStickyBroadcast(Intent intent) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void sendStickyOrderedBroadcast(Intent intent,
            BroadcastReceiver resultReceiver, Handler scheduler, int initialCode, String initialData,
           Bundle initialExtras) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void removeStickyBroadcast(Intent intent) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void sendStickyBroadcastAsUser(Intent intent, UserHandle user) {
        throw new UnsupportedOperationException();
    }

    /** @hide */
    @Override
    public void sendStickyBroadcastAsUser(Intent intent, UserHandle user, Bundle options) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void sendStickyOrderedBroadcastAsUser(Intent intent,
            UserHandle user, BroadcastReceiver resultReceiver,
            Handler scheduler, int initialCode, String initialData,
            Bundle initialExtras) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void removeStickyBroadcastAsUser(Intent intent, UserHandle user) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
            int flags) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
            String broadcastPermission, Handler scheduler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
            String broadcastPermission, Handler scheduler, int flags) {
        throw new UnsupportedOperationException();
    }

    /** @hide */
    @Override
    public Intent registerReceiverAsUser(BroadcastReceiver receiver, UserHandle user,
            IntentFilter filter, String broadcastPermission, Handler scheduler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void unregisterReceiver(BroadcastReceiver receiver) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ComponentName startService(Intent service) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ComponentName startForegroundService(Intent service) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean stopService(Intent service) {
        throw new UnsupportedOperationException();
    }

    /** @hide */
    @Override
    public ComponentName startServiceAsUser(Intent service, UserHandle user) {
        throw new UnsupportedOperationException();
    }

    /** @hide */
    @Override
    public ComponentName startForegroundServiceAsUser(Intent service, UserHandle user) {
        throw new UnsupportedOperationException();
    }

    /** @hide */
    @Override
    public boolean stopServiceAsUser(Intent service, UserHandle user) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean bindService(Intent service, ServiceConnection conn, int flags) {
        throw new UnsupportedOperationException();
    }

    /** @hide */
    @Override
    public boolean bindServiceAsUser(Intent service, ServiceConnection conn, int flags,
            UserHandle user) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void unbindService(ServiceConnection conn) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean startInstrumentation(ComponentName className,
            String profileFile, Bundle arguments) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object getSystemService(String name) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getSystemServiceName(Class<?> serviceClass) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int checkPermission(String permission, int pid, int uid) {
        throw new UnsupportedOperationException();
    }

    /** @hide */
    @Override
    public int checkPermission(String permission, int pid, int uid, IBinder callerToken) {
        return checkPermission(permission, pid, uid);
    }

    @Override
    public int checkCallingPermission(String permission) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int checkCallingOrSelfPermission(String permission) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int checkSelfPermission(String permission) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void enforcePermission(
            String permission, int pid, int uid, String message) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void enforceCallingPermission(String permission, String message) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void enforceCallingOrSelfPermission(String permission, String message) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void grantUriPermission(String toPackage, Uri uri, int modeFlags) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void revokeUriPermission(Uri uri, int modeFlags) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void revokeUriPermission(String targetPackage, Uri uri, int modeFlags) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int checkUriPermission(Uri uri, int pid, int uid, int modeFlags) {
        throw new UnsupportedOperationException();
    }

    /** @hide */
    @Override
    public int checkUriPermission(Uri uri, int pid, int uid, int modeFlags, IBinder callerToken) {
        return checkUriPermission(uri, pid, uid, modeFlags);
    }

    @Override
    public int checkCallingUriPermission(Uri uri, int modeFlags) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int checkCallingOrSelfUriPermission(Uri uri, int modeFlags) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int checkUriPermission(Uri uri, String readPermission,
            String writePermission, int pid, int uid, int modeFlags) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void enforceUriPermission(
            Uri uri, int pid, int uid, int modeFlags, String message) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void enforceCallingUriPermission(
            Uri uri, int modeFlags, String message) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void enforceCallingOrSelfUriPermission(
            Uri uri, int modeFlags, String message) {
        throw new UnsupportedOperationException();
    }

    public void enforceUriPermission(
            Uri uri, String readPermission, String writePermission,
            int pid, int uid, int modeFlags, String message) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Context createPackageContext(String packageName, int flags)
            throws PackageManager.NameNotFoundException {
        throw new UnsupportedOperationException();
    }

    /** {@hide} */
    @Override
    public Context createApplicationContext(ApplicationInfo application, int flags)
            throws PackageManager.NameNotFoundException {
        return null;
    }

    /** @hide */
    @Override
    public Context createContextForSplit(String splitName)
            throws PackageManager.NameNotFoundException {
        throw new UnsupportedOperationException();
    }

    /** {@hide} */
    @Override
    public Context createPackageContextAsUser(String packageName, int flags, UserHandle user)
            throws PackageManager.NameNotFoundException {
        throw new UnsupportedOperationException();
    }

    /** {@hide} */
    @Override
    public int getUserId() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Context createConfigurationContext(Configuration overrideConfiguration) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Context createDisplayContext(Display display) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isRestricted() {
        throw new UnsupportedOperationException();
    }

    /** @hide */
    @Override
    public DisplayAdjustments getDisplayAdjustments(int displayId) {
        throw new UnsupportedOperationException();
    }

    /** @hide */
    @Override
    public Display getDisplay() {
        throw new UnsupportedOperationException();
    }

    /** @hide */
    @Override
    public void updateDisplay(int displayId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public File[] getExternalFilesDirs(String type) {
        throw new UnsupportedOperationException();
    }

    @Override
    public File[] getObbDirs() {
        throw new UnsupportedOperationException();
    }

    @Override
    public File[] getExternalCacheDirs() {
        throw new UnsupportedOperationException();
    }

    @Override
    public File[] getExternalMediaDirs() {
        throw new UnsupportedOperationException();
    }

    /** @hide **/
    @Override
    public File getPreloadsFileCache() { throw new UnsupportedOperationException(); }

    @Override
    public Context createDeviceProtectedStorageContext() {
        throw new UnsupportedOperationException();
    }

    /** {@hide} */
    @SystemApi
    @Override
    public Context createCredentialProtectedStorageContext() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isDeviceProtectedStorage() {
        throw new UnsupportedOperationException();
    }

    /** {@hide} */
    @SystemApi
    @Override
    public boolean isCredentialProtectedStorage() {
        throw new UnsupportedOperationException();
    }

    /** {@hide} */
    @Override
    public boolean canLoadUnsafeResources() {
        throw new UnsupportedOperationException();
    }

    /** {@hide} */
    @Override
    public IBinder getActivityToken() {
        throw new UnsupportedOperationException();
    }

    /** {@hide} */
    @Override
    public IServiceConnection getServiceDispatcher(ServiceConnection conn, Handler handler,
            int flags) {
        throw new UnsupportedOperationException();
    }

    /** {@hide} */
    @Override
    public IApplicationThread getIApplicationThread() {
        throw new UnsupportedOperationException();
    }

    /** {@hide} */
    @Override
    public Handler getMainThreadHandler() {
        throw new UnsupportedOperationException();
    }
}
